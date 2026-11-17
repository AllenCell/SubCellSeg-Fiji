package org.allencell.subcellseg.fiji;

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
import java.util.Arrays;
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
 * Fiji command that runs SubCellSeg (generalist 3D intracellular-structure
 * segmentation) on the active image.
 *
 * The heavy lifting happens in a Python process (the subcellseg package). This
 * class only exports the selected channel frame-by-frame as TIFF, calls the
 * bridge script and reads the results back as ImageJ hyperstacks.
 */
@Plugin(type = Command.class, menuPath = "Plugins>Segmentation>SubCellSeg...", headless = true,
        description = "Segment intracellular structures in a 3D fluorescence volume with SubCellSeg.")
public class SubCellSegCommand implements Command {

    static final String OUT_MASK = "Mask";
    static final String OUT_PROB = "Probability map";
    static final String OUT_BOTH = "Mask + probability map";

    @Parameter
    private LogService log;

    @Parameter
    private StatusService status;

    @Parameter(label = "Image", required = false)
    private ImagePlus imp;

    // ------------------------------------------------------------------ SubCellSeg parameters

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String headerMain = "<html><b>SubCellSeg parameters</b></html>";

    @Parameter(label = "Channel to segment", min = "1",
            description = "1-based index of the structure channel. Ignored for single-channel images.")
    private int channel = 1;

    @Parameter(label = "Output", choices = {OUT_MASK, OUT_PROB, OUT_BOTH},
            description = "Mask: 8-bit binary (0/255). Probability map: 8-bit, 0-255 = probability 0-1.")
    private String output = OUT_MASK;

    @Parameter(label = "Probability threshold", min = "0.05", max = "0.95", stepSize = "0.05",
            description = "Voxels with a probability above the threshold become foreground. 0.5 is the standard setting.")
    private double threshold = 0.5;

    @Parameter(label = "Minimum object size (voxels)", min = "0",
            description = "Remove 3D objects (voxels touching face to face) smaller than this many voxels (0 = keep all).")
    private int min_size = 0;

    // ------------------------------------------------------------------ Advanced options

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String headerAdvanced = "<html><b>Advanced options</b></html>";

    @Parameter(label = "Model checkpoint", required = false, style = "open",
            description = "Leave empty to use the pretrained SubCellSeg model (downloaded once, on the first run). "
                    + "Choose a file to use a model you fine-tuned yourself.")
    private File checkpoint;

    @Parameter(label = "Device", choices = {"auto", "gpu", "cpu"},
            description = "auto picks the GPU when PyTorch can see one.")
    private String device = "auto";

    // ------------------------------------------------------------------ Environment

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String headerEnv = "<html><b>Python environment</b> (remembered between runs)</html>";

    @Parameter(label = "Python environment", required = false, style = "both",
            description = "Leave empty if you installed SubCellSeg with its standard instructions: it is found "
                    + "automatically. Otherwise choose the conda env / venv folder (or python executable) where "
                    + "the subcellseg package is installed.")
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
            if (imp == null || imp.getWidth() == 0 || imp.getProcessor() == null) {
                // null in the GUI; headless SciJava may hand over an empty placeholder ImagePlus instead
                imp = imageOrSample();
                if (imp == null) {
                    return;   // the user declined the sample image
                }
            }
            ensureEngine();
            work = Files.createTempDirectory("subcellseg_fiji_");
            runInternal(work);
            deleteRecursively(work);
            if (GraphicsEnvironment.isHeadless()) {
                // No display post-processor in headless mode: register the results so that
                // macros (getTitle, saveAs, ...) see the mask as the current image.
                registerHeadless(probability);
                registerHeadless(mask);
            }
            final double secs = (System.currentTimeMillis() - t0) / 1000.0;
            final String done = String.format("SubCellSeg finished in %.1f s", secs);
            status.showStatus(done);
            log.info(done);
        } catch (final Exception e) {
            status.showStatus("SubCellSeg failed");
            log.error("SubCellSeg failed: " + e.getMessage(), e);
            final String where = work == null ? "" : "\n\nTemporary files kept for inspection in:\n" + work;
            IJ.error("SubCellSeg", e.getMessage() + where);
        }
    }

    private static boolean interactive() {
        return !GraphicsEnvironment.isHeadless() && !IJ.macroRunning() && !Interpreter.isBatchMode();
    }

    /**
     * No image was open: offer to open the bundled sample (GUI only). Returns null if the user
     * declines; throws in headless / macro mode, where a dialog is not an option.
     */
    private ImagePlus imageOrSample() throws IOException {
        if (!interactive()) {
            throw new IllegalArgumentException("No image is open.");
        }
        final boolean yes = IJ.showMessageWithCancel("SubCellSeg",
                "No image is open.\n \n"
                + "Open the bundled sample image (nuclear speckles, SON-mEGFP, 40 slices) and segment it?\n"
                + "It is also available under File > Open Samples > SubCellSeg nuclear speckles (3D).");
        if (!yes) {
            return null;
        }
        final ImagePlus sample = SubCellSegSampleCommand.open();
        sample.show();
        return sample;
    }

    /**
     * First run: if no SubCellSeg Python environment can be found and the user did not point the
     * plugin at one, offer to install it right away (what "SubCellSeg Setup..." does with its
     * defaults). In headless / macro mode nothing is asked; the run fails with the usual help text.
     */
    private void ensureEngine() throws Exception {
        if (python_env != null && !python_env.getPath().trim().isEmpty()) {
            return;
        }
        final String fromEnv = System.getenv(SubCellSegRunner.PYTHON_ENV_VAR);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            return;
        }
        if (SubCellSegRunner.findUvToolPython() != null || !interactive()) {
            return;
        }
        final boolean yes = IJ.showMessageWithCancel("SubCellSeg: first run",
                "SubCellSeg's Python engine (PyTorch and the model) is not installed yet.\n \n"
                + "Install it now? This downloads up to 3 GB and takes a few minutes;\n"
                + "the segmentation starts as soon as it is done. Progress appears in the\n"
                + "status bar and the Log window.\n \n"
                + "Press Cancel if SubCellSeg is already installed somewhere else: the plugin\n"
                + "then uses 'python' from the PATH, or the folder set under 'Python environment'.");
        if (!yes) {
            return;
        }
        final String report = SubCellSegSetupCommand.quickInstall(log, status);
        log.info("SubCellSeg Setup:\n" + report);
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
            log.warn("SubCellSeg is a 3D model. Running on a single 2D slice works but results may be poor.");
        }

        // 1. Export the selected channel, one TIFF per time frame.
        final Path inDir = Files.createDirectories(work.resolve("in"));
        final Path outDir = Files.createDirectories(work.resolve("out"));
        status.showStatus("SubCellSeg: exporting " + nT + " frame(s)...");
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
        final SubCellSegRunner runner = new SubCellSegRunner(log, status);
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
        status.showStatus("SubCellSeg: loading results...");
        final String base = stripExtension(imp.getTitle());
        if (wantMask) {
            mask = assemble(outDir, "mask", nT, nZ, base + "_SubCellSeg_mask");
            if (min_size > 0) {
                status.showStatus("SubCellSeg: removing small objects...");
                long removed = 0;
                final ImageStack all = mask.getStack();
                for (int t = 0; t < nT; t++) {
                    removed += removeSmallObjects(all, t * nZ + 1, nZ, min_size);
                }
                log.info("[SubCellSeg] Removed " + removed + " object(s) smaller than " + min_size + " voxels");
            }
        }
        if (wantProb) {
            probability = assemble(outDir, "prob", nT, nZ, base + "_SubCellSeg_prob");
        }
    }

    /**
     * Remove foreground objects with fewer than {@code minSize} voxels from one 3D volume of an
     * 8-bit stack, in place. An object is a group of non-zero voxels that touch face to face
     * (6-connectivity). An object of exactly {@code minSize} voxels is kept.
     *
     * @param stack      8-bit stack; non-zero voxels are foreground
     * @param firstSlice 1-based index of the first slice of the volume
     * @param nZ         number of slices in the volume
     * @param minSize    objects smaller than this are set to 0
     * @return the number of objects removed
     */
    public static long removeSmallObjects(final ImageStack stack, final int firstSlice, final int nZ,
            final int minSize) {
        final int w = stack.getWidth();
        final int h = stack.getHeight();
        final byte[][] px = new byte[nZ][];
        for (int z = 0; z < nZ; z++) {
            final Object slice = stack.getPixels(firstSlice + z);
            if (!(slice instanceof byte[])) {
                throw new IllegalArgumentException("removeSmallObjects needs an 8-bit stack");
            }
            px[z] = (byte[]) slice;
        }
        // Each foreground voxel is visited once and marked SEEN when queued; at the end every
        // SEEN voxel becomes 255 again. Only the first minSize voxels of an object are
        // remembered, which is all that is needed to erase a small one.
        final int[] memberZ = new int[minSize];
        final int[] memberI = new int[minSize];
        int[] todoZ = new int[1 << 16];
        int[] todoI = new int[1 << 16];
        long removed = 0;

        for (int z0 = 0; z0 < nZ; z0++) {
            final byte[] start = px[z0];
            for (int i0 = 0; i0 < start.length; i0++) {
                if (!isNew(start[i0])) {
                    continue;
                }
                long size = 0;
                int top = 1;
                todoZ[0] = z0;
                todoI[0] = i0;
                start[i0] = SEEN;
                while (top > 0) {
                    top--;
                    final int z = todoZ[top];
                    final int i = todoI[top];
                    if (size < minSize) {
                        memberZ[(int) size] = z;
                        memberI[(int) size] = i;
                    }
                    size++;
                    if (top + 6 > todoZ.length) {
                        todoZ = Arrays.copyOf(todoZ, todoZ.length * 2);
                        todoI = Arrays.copyOf(todoI, todoI.length * 2);
                    }
                    final int x = i % w;
                    final int y = i / w;
                    if (x > 0 && isNew(px[z][i - 1])) {
                        px[z][i - 1] = SEEN;
                        todoZ[top] = z;
                        todoI[top++] = i - 1;
                    }
                    if (x < w - 1 && isNew(px[z][i + 1])) {
                        px[z][i + 1] = SEEN;
                        todoZ[top] = z;
                        todoI[top++] = i + 1;
                    }
                    if (y > 0 && isNew(px[z][i - w])) {
                        px[z][i - w] = SEEN;
                        todoZ[top] = z;
                        todoI[top++] = i - w;
                    }
                    if (y < h - 1 && isNew(px[z][i + w])) {
                        px[z][i + w] = SEEN;
                        todoZ[top] = z;
                        todoI[top++] = i + w;
                    }
                    if (z > 0 && isNew(px[z - 1][i])) {
                        px[z - 1][i] = SEEN;
                        todoZ[top] = z - 1;
                        todoI[top++] = i;
                    }
                    if (z < nZ - 1 && isNew(px[z + 1][i])) {
                        px[z + 1][i] = SEEN;
                        todoZ[top] = z + 1;
                        todoI[top++] = i;
                    }
                }
                if (size < minSize) {
                    for (int k = 0; k < size; k++) {
                        px[memberZ[k]][memberI[k]] = 0;
                    }
                    removed++;
                }
            }
        }
        for (final byte[] plane : px) {
            for (int i = 0; i < plane.length; i++) {
                if (plane[i] == SEEN) {
                    plane[i] = (byte) 255;
                }
            }
        }
        return removed;
    }

    private static final byte SEEN = 1;

    private static boolean isNew(final byte value) {
        return value != 0 && value != SEEN;
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
    static void registerHeadless(final ImagePlus img) {
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
