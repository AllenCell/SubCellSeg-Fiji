package org.allencell.subcellseg.fiji;

import ij.IJ;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Installs, updates or checks the SubCellSeg Python package that the SubCellSeg
 * command runs, so that users never have to open a terminal.
 *
 * It does exactly what the SubCellSeg installation instructions say: install
 * <a href="https://docs.astral.sh/uv/">uv</a> if it is missing, then
 * {@code uv tool install --python 3.12 --torch-backend auto <SubCellSeg>}. The
 * resulting environment is the one {@link SubCellSegRunner} finds automatically.
 */
@Plugin(type = Command.class, menuPath = "Plugins>Segmentation>SubCellSeg Setup...", headless = true,
        description = "Install or update the SubCellSeg Python package used by the SubCellSeg command.")
public class SubCellSegSetupCommand implements Command {

    /** What "Install or update" installs by default: the current SubCellSeg source archive. */
    public static final String DEFAULT_SOURCE = "https://github.com/AllenCell/SubCellSeg/archive/refs/heads/main.zip";

    static final String ACT_INSTALL = "Install or update SubCellSeg";
    static final String ACT_CHECK = "Check the installation";
    static final String ACT_REINSTALL = "Reinstall from scratch";

    static final String BACKEND_AUTO = "auto (detect the GPU)";
    static final String PYTHON_VERSION = "3.12";
    static final String PACKAGE = "subcellseg";

    private static final String UV_INSTALL_SH = "curl -LsSf https://astral.sh/uv/install.sh | sh";
    private static final String UV_INSTALL_PS = "irm https://astral.sh/uv/install.ps1 | iex";
    private static final int TAIL = 30;

    @Parameter
    private LogService log;

    @Parameter
    private StatusService status;

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String header = "<html>Installs the SubCellSeg Python package for the current user, with <b>uv</b>.<br>"
            + "Needs an internet connection. The first install downloads up to 3 GB (PyTorch with GPU support)<br>"
            + "and takes a few minutes. Progress appears in the status bar and the Log window.</html>";

    @Parameter(label = "Action", choices = {ACT_INSTALL, ACT_CHECK, ACT_REINSTALL},
            description = "Install or update: installs SubCellSeg, or updates it to the latest version. "
                    + "Check: only reports what is installed. Reinstall: starts over (fixes a broken installation).")
    private String action = ACT_INSTALL;

    @Parameter(label = "PyTorch build", choices = {BACKEND_AUTO, "cpu", "cu126", "cu128", "cu130"},
            description = "auto looks at the graphics card and installs the matching CUDA build of PyTorch "
                    + "(CPU-only when there is none). On macOS the standard PyTorch build is always used.")
    private String torch_backend = BACKEND_AUTO;

    @Parameter(label = "Extra image formats (CZI, ND2, OME-Zarr)",
            description = "Only matters for the subcellseg command line; Fiji reads the images for the plugin.")
    private boolean extra_formats = false;

    @Parameter(label = "Download the model now (65 MB)",
            description = "Otherwise it is downloaded on the first segmentation.")
    private boolean download_model = true;

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String headerAdvanced = "<html><b>Advanced</b></html>";

    @Parameter(label = "Install from", persist = false,
            description = "What to install: the SubCellSeg GitHub archive (default), another archive URL, "
                    + "or a local folder that holds the SubCellSeg source code.")
    private String source = DEFAULT_SOURCE;

    @Parameter(label = "Install location", required = false,
            description = "Leave empty to use uv's standard per-user folders. If the installation fails with "
                    + "'This program is blocked by group policy' (managed Windows PCs often refuse to run programs "
                    + "from the user profile), put a folder inside the Fiji folder here, e.g. <Fiji>/subcellseg. "
                    + "The plugin remembers it.")
    private String install_dir = "";

    /** Human-readable summary of what was done, or "FAILED: ..." . */
    @Parameter(type = ItemIO.OUTPUT)
    private String report;

    @Override
    public void run() {
        final StringBuilder summary = new StringBuilder();
        try {
            final boolean check = ACT_CHECK.equals(action);
            if (!check) {
                install(summary);
            }
            final String python = pythonForCheck(check);
            info(python, summary);
            if (download_model) {
                downloadModel(python, summary);
            }
            summary.append(check ? "" : "\nSubCellSeg is ready: use Plugins > Segmentation > SubCellSeg...");
            report = summary.toString().trim();
            status.showStatus("SubCellSeg Setup finished");
            log.info("SubCellSeg Setup:\n" + report);
            if (!GraphicsEnvironment.isHeadless()) {
                IJ.showMessage("SubCellSeg Setup", report);
            }
        } catch (final Exception e) {
            report = "FAILED: " + e.getMessage();
            status.showStatus("SubCellSeg Setup failed");
            log.error("SubCellSeg Setup failed: " + e.getMessage(), e);
            IJ.error("SubCellSeg Setup", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ steps

    private void install(final StringBuilder summary) throws IOException, InterruptedException {
        File uv = SubCellSegRunner.findUv();
        if (uv == null) {
            status.showStatus("SubCellSeg Setup: installing uv...");
            final List<String> cmd = isWindows()
                    ? Arrays.asList("powershell", "-NoProfile", "-ExecutionPolicy", "ByPass", "-Command", UV_INSTALL_PS)
                    : Arrays.asList("sh", "-c", UV_INSTALL_SH);
            stream(cmd, "uv installer");
            uv = SubCellSegRunner.findUv();
            if (uv == null) {
                throw new IOException("uv was installed but cannot be found in "
                        + (installDir() != null ? new File(installDir(), "bin") : new File(home(), ".local/bin"))
                        + ".\nOpen a terminal, run 'uv --version', and if that works please report this problem.");
            }
            summary.append("Installed uv (").append(uv).append(")\n");
        } else {
            log.info("Using uv: " + uv);
        }

        final boolean present = SubCellSegRunner.findUvToolPython() != null;
        final List<String> cmd = new ArrayList<>();
        cmd.add(uv.getAbsolutePath());
        cmd.add("tool");
        cmd.add("install");
        cmd.add("--python");
        cmd.add(PYTHON_VERSION);
        cmd.add("--force");   // replace a stale "subcellseg" launcher left by an earlier installation
        if (installDir() != null) {
            // Never pick up a Python from elsewhere: it could live in a folder that is not allowed to run.
            cmd.add("--python-preference");
            cmd.add("only-managed");
        }
        if (!isMac()) {
            // PyTorch has no CUDA builds for macOS; the default PyPI build is the right one there.
            cmd.add("--torch-backend");
            cmd.add(BACKEND_AUTO.equals(torch_backend) ? "auto" : torch_backend);
        }
        if (ACT_REINSTALL.equals(action)) {
            cmd.add("--reinstall");
        } else if (present) {
            // Fetch the package again even if the archive URL did not change (= update).
            cmd.add("--reinstall-package");
            cmd.add(PACKAGE);
        }
        cmd.add(installSpec());

        status.showStatus("SubCellSeg Setup: " + (present ? "updating" : "installing") + " SubCellSeg (this takes a few minutes)...");
        stream(cmd, "uv");
        rememberInstallDir();
        summary.append(present ? "Updated" : "Installed").append(" SubCellSeg from ").append(source.trim()).append('\n');
        if (installDir() != null) {
            summary.append("Install location: ").append(installDir()).append('\n');
        }

        if (SubCellSegRunner.findUvToolPython() == null) {
            throw new IOException("SubCellSeg was installed, but its Python environment was not found where the "
                    + "plugin looks for it (" + SubCellSegRunner.uvToolRootsDescription() + ").\n"
                    + "Run 'uv tool dir' in a terminal and set 'Python environment' in the SubCellSeg dialog to "
                    + "<that folder>" + File.separator + PACKAGE + ".");
        }
    }

    /** The custom install location from the dialog, or null for uv's defaults. */
    File installDir() {
        return install_dir == null || install_dir.trim().isEmpty() ? null : new File(install_dir.trim()).getAbsoluteFile();
    }

    /**
     * Store (or clear) the custom install location in the ImageJ preferences, so that the segmentation
     * command and later Setup runs find the environment there.
     */
    private void rememberInstallDir() {
        final File dir = installDir();
        ij.Prefs.set(SubCellSegRunner.INSTALL_DIR_PREF, dir == null ? "" : dir.getPath());
        ij.Prefs.savePreferences();
    }

    /** The requirement passed to uv: the source, with the [all] extra when asked for. */
    String installSpec() {
        String spec = source.trim();
        if (spec.isEmpty()) {
            spec = DEFAULT_SOURCE;
        }
        if (!extra_formats) {
            return spec;
        }
        final File local = new File(spec);
        if (local.isDirectory()) {
            // "name[extra] @ <url>" needs a URL, so turn a local folder into a file:// URL.
            spec = local.toURI().toString().replaceFirst("^file:/(?!/)", "file:///");
        }
        return PACKAGE + "[all] @ " + spec;
    }

    private String pythonForCheck(final boolean checkOnly) {
        final File tool = SubCellSegRunner.findUvToolPython();
        if (tool != null) {
            return tool.getAbsolutePath();
        }
        if (checkOnly) {
            // Fall back to the same search as the segmentation command (SUBCELLSEG_PYTHON, PATH).
            return new SubCellSegRunner(log, status).resolvePython(null);
        }
        throw new IllegalStateException("The SubCellSeg Python environment was not found.");
    }

    private void info(final String python, final StringBuilder summary) throws IOException, InterruptedException {
        status.showStatus("SubCellSeg Setup: checking the installation...");
        final List<String> out = new ArrayList<>();
        final int code = capture(Arrays.asList(python, "-m", PACKAGE, "info"), out);
        if (code != 0) {
            final String text = String.join("\n", out);
            if (text.contains("No module named")) {
                throw new IOException("SubCellSeg is not installed for " + python + ".\n"
                        + "Choose 'Install or update SubCellSeg' and press OK.");
            }
            throw new IOException("'subcellseg info' failed (exit " + code + "):\n" + text);
        }
        summary.append("Python: ").append(python).append('\n');
        for (final String line : out) {
            summary.append(line).append('\n');
        }
    }

    private void downloadModel(final String python, final StringBuilder summary) throws IOException, InterruptedException {
        status.showStatus("SubCellSeg Setup: downloading the model (65 MB)...");
        final List<String> out = new ArrayList<>();
        final int code = capture(Arrays.asList(python, "-c",
                "from subcellseg.weights import fetch_pretrained; print(fetch_pretrained())"), out);
        if (code != 0) {
            throw new IOException("The model could not be downloaded:\n" + String.join("\n", out)
                    + "\n\nYou can download subcellseg_v1.pt in a web browser from " + SubCellSegRunner.WEIGHTS_URL
                    + " and select it under 'Model checkpoint' in the SubCellSeg dialog.");
        }
        summary.append("Model: ").append(out.isEmpty() ? "downloaded" : out.get(out.size() - 1)).append('\n');
    }

    // ------------------------------------------------------------------ process helpers

    /** Run a long command, relaying every output line to the log and the status bar. */
    private void stream(final List<String> cmd, final String label) throws IOException, InterruptedException {
        log.info(label + " command: " + String.join(" ", cmd));
        final Process p = start(cmd);
        final Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String raw;
            while ((raw = r.readLine()) != null) {
                for (final String part : raw.split("\r")) {
                    final String line = part.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    log.info("[" + label + "] " + line);
                    status.showStatus("SubCellSeg Setup: " + (line.length() > 90 ? line.substring(0, 90) + "..." : line));
                    tail.addLast(line);
                    if (tail.size() > TAIL) {
                        tail.removeFirst();
                    }
                }
            }
        }
        final int code = p.waitFor();
        if (code != 0) {
            final String text = String.join("\n", tail);
            final String lower = text.toLowerCase();
            final boolean policy = lower.matches("(?s).*(group policy|os error 1260|blocked by).*");
            final boolean network = lower.matches("(?s).*(download|network|connect|request|timed out|tls|certificate).*");
            String hint = "";
            if (policy) {
                final File fiji = SubCellSegRunner.fijiInstallDir();
                hint = "\n\nThis computer does not allow programs to run from the user profile folder. Run Setup again "
                        + "with 'Install location' set to a folder inside the Fiji folder"
                        + (fiji != null ? ", e.g. " + fiji.getPath() : "") + ".";
            } else if (network) {
                hint = "\n\nIf a download was blocked, your IT department may need to allow astral.sh, "
                        + "github.com, pypi.org and download.pytorch.org.";
            }
            throw new IOException(label + " failed (exit code " + code + "). Last output:\n" + text + hint);
        }
    }

    /** Run a short command and collect its output lines. Returns the exit code. */
    private int capture(final List<String> cmd, final List<String> out) throws IOException, InterruptedException {
        log.info("command: " + String.join(" ", cmd));
        final Process p = start(cmd);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.replace('\r', ' ').trim();
                if (!line.isEmpty()) {
                    out.add(line);
                    log.debug("[python] " + line);
                }
            }
        }
        return p.waitFor();
    }

    private Process start(final List<String> cmd) throws IOException {
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(home());
        final File dir = installDir();
        if (dir != null) {
            // Keep everything uv creates (its own binary, Pythons, tool environments, launchers, cache)
            // under the chosen folder; the per-user defaults may not be allowed to run programs.
            dir.mkdirs();
            pb.environment().put("UV_INSTALL_DIR", new File(dir, "bin").getPath());
            pb.environment().put("UV_TOOL_BIN_DIR", new File(dir, "bin").getPath());
            pb.environment().put("UV_TOOL_DIR", new File(dir, "tools").getPath());
            pb.environment().put("UV_PYTHON_INSTALL_DIR", new File(dir, "python").getPath());
            pb.environment().put("UV_CACHE_DIR", new File(dir, "cache").getPath());
        }
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("UV_NO_PROGRESS", "1");       // plain lines instead of progress bars
        pb.environment().put("UV_NO_WRAP", "1");
        pb.environment().put("NO_COLOR", "1");
        try {
            return pb.start();
        } catch (final IOException e) {
            throw new IOException("Could not start '" + cmd.get(0) + "': " + e.getMessage(), e);
        }
    }

    private static File home() {
        return new File(System.getProperty("user.home", "."));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
