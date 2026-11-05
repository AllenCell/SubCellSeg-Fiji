package org.allencell.megaseg.fiji;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.io.FileSaver;
import ij.macro.Interpreter;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Fiji command that runs MegaSeg (generalist 3D intracellular-structure
 * segmentation) on the active image.
 *
 * The heavy lifting happens in a Python process (cytodl-mini). This class only
 * exports the selected channel frame-by-frame as TIFF, calls the bridge script
 * and reads the results back as ImageJ hyperstacks.
 */
@Plugin(type = Command.class, menuPath = "Plugins>Segmentation>MegaSeg...", headless = true,
        description = "Segment intracellular structures in a 3D fluorescence volume with MegaSeg.")
public class MegaSegCommand implements Command {

    static final String OUT_MASK = "Mask";
    static final String OUT_PROB = "Probability map";
    static final String OUT_BOTH = "Mask + probability map";

    @Parameter
    private LogService log;

    @Parameter
    private StatusService status;

    @Parameter(label = "Image")
    private ImagePlus imp;

    // ------------------------------------------------------------------ MegaSeg parameters

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String headerMain = "<html><b>MegaSeg parameters</b></html>";

    @Parameter(label = "Channel to segment", min = "1",
            description = "1-based index of the structure channel. Ignored for single-channel images.")
    private int channel = 1;

    @Parameter(label = "Output", choices = {OUT_MASK, OUT_PROB, OUT_BOTH},
            description = "Mask: 8-bit binary (0/255). Probability map: 8-bit, 0-255 = probability 0-1.")
    private String output = OUT_MASK;

    @Parameter(label = "Probability threshold", min = "0.05", max = "0.95", stepSize = "0.05",
            description = "Voxels with probability >= threshold become foreground. 0.5 reproduces the paper.")
    private double threshold = 0.5;

    @Parameter(label = "Minimum object size (voxels)", min = "0",
            description = "Remove 3D connected components smaller than this many voxels (0 = keep all).")
    private int min_size = 0;

    // ------------------------------------------------------------------ Advanced options

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String headerAdvanced = "<html><b>Advanced options</b></html>";

    @Parameter(label = "Model checkpoint", required = false, style = "open",
            description = "Leave empty to use the default MegaSeg checkpoint (downloaded once to ~/.cache/cytodl-mini).")
    private File checkpoint;

    @Parameter(label = "Device", choices = {"auto", "gpu", "cpu"},
            description = "auto picks the GPU when PyTorch can see one.")
    private String device = "auto";

    // ------------------------------------------------------------------ Environment

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String headerEnv = "<html><b>Python environment</b> (remembered between runs)</html>";

    @Parameter(label = "cytodl-mini environment", required = false, style = "both",
            description = "Conda env / venv folder (or python executable) where cytodl-mini is installed. "
                    + "Leave empty to use the MEGASEG_PYTHON environment variable or 'python' on the PATH.")
    private File python_env;

    // ------------------------------------------------------------------ Outputs
    // Declared probability-first so that, when both are shown, the mask ends up as the
    // active (front) image, which is what a recorded macro's saveAs() expects.

    @Parameter(type = ItemIO.OUTPUT, required = false)
    private ImagePlus probability;

    @Parameter(type = ItemIO.OUTPUT, required = false)
    private ImagePlus mask;

    @Override
    public void run() {
        final long t0 = System.currentTimeMillis();
        Path work = null;
        try {
            work = Files.createTempDirectory("megaseg_fiji_");
            runInternal(work);
            deleteRecursively(work);
            if (GraphicsEnvironment.isHeadless()) {
                // No display post-processor in headless mode: register the results so that
                // macros (getTitle, saveAs, ...) see the mask as the current image.
                registerHeadless(probability);
                registerHeadless(mask);
            }
            final double secs = (System.currentTimeMillis() - t0) / 1000.0;
            final String done = String.format("MegaSeg finished in %.1f s", secs);
            status.showStatus(done);
            log.info(done);
        } catch (final Exception e) {
            status.showStatus("MegaSeg failed");
            log.error("MegaSeg failed: " + e.getMessage(), e);
            final String where = work == null ? "" : "\n\nTemporary files kept for inspection in:\n" + work;
            IJ.error("MegaSeg", e.getMessage() + where);
        }
    }

    private void runInternal(final Path work) throws IOException, InterruptedException {
        final boolean wantMask = !OUT_PROB.equals(output);
        final boolean wantProb = !OUT_MASK.equals(output);

        final int nC = imp.getNChannels();
        final int nZ = imp.getNSlices();
        final int nT = imp.getNFrames();
        final int ch = nC == 1 ? 1 : channel;

        if (imp.getBitDepth() == 24) {
            throw new IllegalArgumentException("RGB images are not supported. Convert to a multi-channel stack first (Image > Type > RGB Stack).");
        }
        if (ch > nC) {
            throw new IllegalArgumentException("Channel " + ch + " requested but the image has only " + nC + " channel(s).");
        }
        if (nZ == 1) {
            log.warn("MegaSeg is a 3D model. Running on a single 2D slice works but results may be poor.");
        }

        // 1. Export the selected channel, one TIFF per time frame.
        final Path inDir = Files.createDirectories(work.resolve("in"));
        final Path outDir = Files.createDirectories(work.resolve("out"));
        status.showStatus("MegaSeg: exporting " + nT + " frame(s)...");
        final ImageStack src = imp.getStack();
        for (int t = 1; t <= nT; t++) {
            final ImageStack stack = new ImageStack(imp.getWidth(), imp.getHeight());
            for (int z = 1; z <= nZ; z++) {
                stack.addSlice(src.getProcessor(imp.getStackIndex(ch, z, t)));
            }
            final ImagePlus frame = new ImagePlus(frameName(t), stack);
            if (!new FileSaver(frame).saveAsTiff(inDir.resolve(frameName(t) + ".tif").toString())) {
                throw new IOException("Could not write temporary TIFF for frame " + t);
            }
        }

        // 2. Run the Python bridge.
        final MegaSegRunner runner = new MegaSegRunner(log, status);
        final String python = runner.resolvePython(python_env);
        final Path bridge = runner.extractBridge(work);

        final List<String> cmd = new ArrayList<>();
        cmd.add(python);
        cmd.add("-u");
        cmd.add(bridge.toString());
        cmd.add("--input");
        cmd.add(inDir.toString());
        cmd.add("--output");
        cmd.add(outDir.toString());
        cmd.add("--device");
        cmd.add(device);
        cmd.add("--threshold");
        cmd.add(Double.toString(threshold));
        cmd.add("--min-size");
        cmd.add(Integer.toString(min_size));
        cmd.add("--outputs");
        cmd.add(wantMask && wantProb ? "mask,prob" : wantMask ? "mask" : "prob");
        if (checkpoint != null && !checkpoint.getPath().trim().isEmpty()) {
            if (!checkpoint.isFile()) {
                throw new IllegalArgumentException("Checkpoint not found: " + checkpoint);
            }
            cmd.add("--checkpoint");
            cmd.add(checkpoint.getAbsolutePath());
        }
        runner.run(cmd, work);

        // 3. Read results back.
        status.showStatus("MegaSeg: loading results...");
        final String base = stripExtension(imp.getTitle());
        if (wantMask) {
            mask = assemble(outDir, "mask", nT, nZ, base + "_MegaSeg_mask");
        }
        if (wantProb) {
            probability = assemble(outDir, "prob", nT, nZ, base + "_MegaSeg_prob");
        }
    }

    private ImagePlus assemble(final Path outDir, final String kind, final int nT, final int nZ, final String title)
            throws IOException {
        ImageStack stack = null;
        for (int t = 1; t <= nT; t++) {
            final Path f = outDir.resolve(frameName(t) + "_" + kind + ".tif");
            if (!Files.isRegularFile(f)) {
                throw new IOException("Expected result file is missing: " + f);
            }
            final ImagePlus frame = IJ.openImage(f.toString());
            if (frame == null) {
                throw new IOException("Could not open result file: " + f);
            }
            if (frame.getWidth() != imp.getWidth() || frame.getHeight() != imp.getHeight()
                    || frame.getStackSize() != nZ) {
                throw new IOException("Result dimensions do not match the input for frame " + t);
            }
            if (stack == null) {
                stack = new ImageStack(frame.getWidth(), frame.getHeight());
            }
            final ImageStack fs = frame.getStack();
            for (int z = 1; z <= nZ; z++) {
                stack.addSlice(fs.getProcessor(z));
            }
        }
        final ImagePlus result = new ImagePlus(title, stack);
        result.setDimensions(1, nZ, nT);
        if (nT > 1) {
            result.setOpenAsHyperStack(true);
        }
        result.setCalibration(imp.getCalibration().copy());
        result.setDisplayRange(0, 255);
        return result;
    }

    /**
     * Headless ImageJ keeps the "current image" per thread. The command runs on a SciJava
     * worker thread while an ImageJ macro waits on its own thread, so make the result the
     * current image for every thread that is executing a macro (and for this one).
     */
    private static void registerHeadless(final ImagePlus img) {
        if (img == null) {
            return;
        }
        img.show();
        WindowManager.setTempCurrentImage(img);
        if (Interpreter.isBatchMode()) {
            Interpreter.addBatchModeImage(img);
        }
        for (final Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            for (final StackTraceElement frame : e.getValue()) {
                if (frame.getClassName().startsWith("ij.macro.")) {
                    WindowManager.setTempCurrentImage(e.getKey(), img);
                    break;
                }
            }
        }
    }

    private static String frameName(final int t) {
        return String.format("frame_%04d", t);
    }

    private static String stripExtension(final String name) {
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void deleteRecursively(final Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (final IOException ignored) {
            // best effort
        }
    }
}
