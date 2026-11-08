package org.allencell.subcellseg.fiji;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.scijava.app.StatusService;
import org.scijava.log.LogService;

/**
 * Finds a Python interpreter, extracts the bundled bridge script and runs it,
 * relaying progress to the Fiji status bar and log.
 */
public class SubCellSegRunner {

    public static final String BRIDGE_RESOURCE = "/subcellseg_bridge.py";
    public static final String PYTHON_ENV_VAR = "SUBCELLSEG_PYTHON";
    public static final String INSTALL_URL = "https://github.com/AllenCell/SubCellSeg";
    public static final String WEIGHTS_URL = INSTALL_URL + "/releases/tag/weights-v1";

    /** Name of the environment that "uv tool install" creates for the subcellseg package. */
    private static final String UV_TOOL_NAME = "subcellseg";

    private static final String STATUS_PREFIX = "SUBCELLSEG_STATUS ";
    private static final String ERROR_PREFIX = "SUBCELLSEG_ERROR ";
    private static final String DONE_MARKER = "SUBCELLSEG_DONE";
    private static final int STDERR_TAIL = 40;

    private static final String[] PYTHON_CANDIDATES = {
            "python.exe", "Scripts/python.exe", "bin/python", "bin/python3",
            ".venv/Scripts/python.exe", ".venv/bin/python", "venv/Scripts/python.exe", "venv/bin/python",
    };

    private final LogService log;
    private final StatusService status;

    public SubCellSegRunner(final LogService log, final StatusService status) {
        this.log = log;
        this.status = status;
    }

    /**
     * Resolve the Python executable. In order: the folder or file chosen in the dialog, the
     * SUBCELLSEG_PYTHON environment variable, the environment made by the standard SubCellSeg
     * installation ("uv tool install"), and finally whatever "python" is on the PATH.
     */
    public String resolvePython(final File env) {
        if (env != null && !env.getPath().trim().isEmpty()) {
            if (env.isFile()) {
                return env.getAbsolutePath();
            }
            if (env.isDirectory()) {
                final File python = pythonIn(env);
                if (python != null) {
                    return python.getAbsolutePath();
                }
                throw new IllegalArgumentException("No Python executable found inside " + env
                        + ".\nPoint 'Python environment' to a conda env / venv folder or directly to python(.exe).");
            }
            throw new IllegalArgumentException("Python environment does not exist: " + env);
        }
        final String fromEnv = System.getenv(PYTHON_ENV_VAR);
        if (fromEnv != null && !fromEnv.trim().isEmpty()) {
            final File f = new File(fromEnv.trim());
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
            if (f.isDirectory()) {
                return resolvePython(f);
            }
            throw new IllegalArgumentException(PYTHON_ENV_VAR + " points to a missing path: " + fromEnv);
        }
        final File tool = findUvToolPython();
        if (tool != null) {
            return tool.getAbsolutePath();
        }
        return isWindows() ? "python" : "python3";
    }

    /** The Python of the environment created by "uv tool install" for SubCellSeg, or null. */
    static File findUvToolPython() {
        for (final File root : uvToolRoots()) {
            final File python = pythonIn(new File(root, UV_TOOL_NAME));
            if (python != null) {
                return python;
            }
        }
        return null;
    }

    /** Where uv keeps tool environments (UV_TOOL_DIR, then the platform default). */
    static List<File> uvToolRoots() {
        final List<File> roots = new ArrayList<>();
        final String toolDir = System.getenv("UV_TOOL_DIR");
        if (toolDir != null && !toolDir.trim().isEmpty()) {
            roots.add(new File(toolDir.trim()));
        }
        final String appData = System.getenv("APPDATA");
        if (isWindows() && appData != null && !appData.trim().isEmpty()) {
            roots.add(new File(appData.trim(), "uv/tools"));
        }
        final String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.trim().isEmpty()) {
            roots.add(new File(xdg.trim(), "uv/tools"));
        }
        roots.add(new File(System.getProperty("user.home", ""), ".local/share/uv/tools"));
        return roots;
    }

    static String uvToolRootsDescription() {
        final List<String> names = new ArrayList<>();
        for (final File root : uvToolRoots()) {
            names.add(root.getPath());
        }
        return String.join(", ", names);
    }

    /**
     * The uv executable: on the PATH, or where uv's installer puts it (~/.local/bin). Fiji started
     * from the desktop often has a minimal PATH, hence the explicit fallback.
     */
    public static File findUv() {
        final String exe = isWindows() ? "uv.exe" : "uv";
        final String path = System.getenv("PATH");
        if (path != null) {
            for (final String dir : path.split(File.pathSeparator)) {
                if (dir.trim().isEmpty()) {
                    continue;
                }
                final File f = new File(dir.trim(), exe);
                if (f.isFile()) {
                    return f;
                }
            }
        }
        final File home = new File(System.getProperty("user.home", ""));
        final File[] fallbacks = {new File(home, ".local/bin/" + exe), new File(home, ".cargo/bin/" + exe)};
        for (final File f : fallbacks) {
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private static File pythonIn(final File dir) {
        if (!dir.isDirectory()) {
            return null;
        }
        for (final String c : PYTHON_CANDIDATES) {
            final File f = new File(dir, c);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    /** Copy the bridge script from the jar into the working directory. */
    public Path extractBridge(final Path dir) throws IOException {
        try (InputStream in = SubCellSegRunner.class.getResourceAsStream(BRIDGE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bridge script " + BRIDGE_RESOURCE + " is missing from the plugin jar.");
            }
            final Path target = dir.resolve("subcellseg_bridge.py");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    /** Run the command, streaming stdout to the log/status bar. Throws with a readable message on failure. */
    public void run(final List<String> cmd, final Path workDir) throws IOException, InterruptedException {
        log.info("SubCellSeg command: " + String.join(" ", cmd));
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        final Process process;
        try {
            process = pb.start();
        } catch (final IOException e) {
            throw new IOException("Could not start Python (" + cmd.get(0) + "): " + e.getMessage()
                    + "\n" + NOT_INSTALLED_HELP, e);
        }

        final Deque<String> errTail = new ArrayDeque<>();
        final Thread errThread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    final String clean = line.replace('\r', ' ').trim();
                    if (clean.isEmpty()) {
                        continue;
                    }
                    synchronized (errTail) {
                        errTail.addLast(clean);
                        if (errTail.size() > STDERR_TAIL) {
                            errTail.removeFirst();
                        }
                    }
                    log.debug("[python] " + clean);
                }
            } catch (final IOException ignored) {
                // process ended
            }
        }, "subcellseg-stderr");
        errThread.setDaemon(true);
        errThread.start();

        String bridgeError = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(STATUS_PREFIX)) {
                    final String msg = line.substring(STATUS_PREFIX.length());
                    status.showStatus("SubCellSeg: " + msg);
                    log.info("[SubCellSeg] " + msg);
                } else if (line.startsWith(ERROR_PREFIX)) {
                    bridgeError = line.substring(ERROR_PREFIX.length());
                } else if (!line.trim().isEmpty() && !line.startsWith(DONE_MARKER)) {
                    log.info("[python] " + line);
                }
            }
        }

        final int code = process.waitFor();
        errThread.join(5000);
        if (code != 0) {
            final String tail;
            synchronized (errTail) {
                tail = String.join("\n", errTail);
            }
            throw new IOException(friendlyError(code, bridgeError, tail));
        }
    }

    private static final String NOT_INSTALLED_HELP =
            "Run Plugins > Segmentation > SubCellSeg Setup... to install it (or follow " + INSTALL_URL + "), then try again.\n"
            + "If SubCellSeg is already installed in a conda env / venv, set 'Python environment' to that folder.";

    private static String friendlyError(final int code, final String bridgeError, final String stderrTail) {
        final StringBuilder sb = new StringBuilder();
        final String all = (bridgeError == null ? "" : bridgeError) + "\n" + stderrTail;
        if (code == 9009 || all.contains("Python was not found")) {
            sb.append("Python was not found.\n").append(NOT_INSTALLED_HELP);
        } else if (all.contains("No module named 'subcellseg'") || all.contains("No module named subcellseg")) {
            sb.append("SubCellSeg is not installed in the Python environment that the plugin used.\n")
              .append(NOT_INSTALLED_HELP);
        } else if (all.contains("No module named 'torch'")) {
            sb.append("PyTorch is not installed in the selected Python environment.\n").append(NOT_INSTALLED_HELP);
        } else if (all.contains("CUDA out of memory") || all.contains("OutOfMemoryError")) {
            sb.append("The GPU ran out of memory. Try Device = cpu, or crop / downsample the image.");
        } else if (all.contains("Could not download the pretrained weights")
                || all.contains("No download location is configured")) {
            sb.append("The SubCellSeg model could not be downloaded.\n")
              .append("Download the file subcellseg_v1.pt in a web browser from ").append(WEIGHTS_URL)
              .append("\nand select it under 'Model checkpoint'.");
        } else if (bridgeError != null) {
            sb.append(bridgeError);
        } else {
            sb.append("SubCellSeg exited with code ").append(code).append('.');
        }
        if (!stderrTail.isEmpty()) {
            sb.append("\n\nLast Python output:\n").append(stderrTail);
        }
        return sb.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
