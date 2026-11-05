package org.allencell.megaseg.fiji;

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
import java.util.Deque;
import java.util.List;

import org.scijava.app.StatusService;
import org.scijava.log.LogService;

/**
 * Finds a Python interpreter, extracts the bundled bridge script and runs it,
 * relaying progress to the Fiji status bar and log.
 */
public class MegaSegRunner {

    public static final String BRIDGE_RESOURCE = "/megaseg_bridge.py";
    public static final String PYTHON_ENV_VAR = "MEGASEG_PYTHON";

    private static final String STATUS_PREFIX = "MEGASEG_STATUS ";
    private static final String ERROR_PREFIX = "MEGASEG_ERROR ";
    private static final int STDERR_TAIL = 40;

    private static final String[] PYTHON_CANDIDATES = {
            "python.exe", "Scripts/python.exe", "bin/python", "bin/python3",
            ".venv/Scripts/python.exe", ".venv/bin/python", "venv/Scripts/python.exe", "venv/bin/python",
    };

    private final LogService log;
    private final StatusService status;

    public MegaSegRunner(final LogService log, final StatusService status) {
        this.log = log;
        this.status = status;
    }

    /** Resolve the Python executable from a folder, a file, the environment variable, or the PATH. */
    public String resolvePython(final File env) {
        if (env != null && !env.getPath().trim().isEmpty()) {
            if (env.isFile()) {
                return env.getAbsolutePath();
            }
            if (env.isDirectory()) {
                for (final String c : PYTHON_CANDIDATES) {
                    final File f = new File(env, c);
                    if (f.isFile()) {
                        return f.getAbsolutePath();
                    }
                }
                throw new IllegalArgumentException("No Python executable found inside " + env
                        + ".\nPoint 'cytodl-mini environment' to a conda env / venv folder or directly to python(.exe).");
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
        return isWindows() ? "python" : "python3";
    }

    /** Copy the bridge script from the jar into the working directory. */
    public Path extractBridge(final Path dir) throws IOException {
        try (InputStream in = MegaSegRunner.class.getResourceAsStream(BRIDGE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bridge script " + BRIDGE_RESOURCE + " is missing from the plugin jar.");
            }
            final Path target = dir.resolve("megaseg_bridge.py");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    /** Run the command, streaming stdout to the log/status bar. Throws with a readable message on failure. */
    public void run(final List<String> cmd, final Path workDir) throws IOException, InterruptedException {
        log.info("MegaSeg command: " + String.join(" ", cmd));
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());
        pb.environment().put("PYTHONUNBUFFERED", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");

        final Process process;
        try {
            process = pb.start();
        } catch (final IOException e) {
            throw new IOException("Could not start Python (" + cmd.get(0) + "): " + e.getMessage()
                    + "\nSet 'cytodl-mini environment' to the folder of a conda env / venv where cytodl-mini is installed.", e);
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
        }, "megaseg-stderr");
        errThread.setDaemon(true);
        errThread.start();

        String bridgeError = null;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(STATUS_PREFIX)) {
                    final String msg = line.substring(STATUS_PREFIX.length());
                    status.showStatus("MegaSeg: " + msg);
                    log.info("[MegaSeg] " + msg);
                } else if (line.startsWith(ERROR_PREFIX)) {
                    bridgeError = line.substring(ERROR_PREFIX.length());
                } else if (!line.trim().isEmpty()) {
                    log.info("[cytodl-mini] " + line);
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

    private static String friendlyError(final int code, final String bridgeError, final String stderrTail) {
        final StringBuilder sb = new StringBuilder();
        final String all = (bridgeError == null ? "" : bridgeError) + "\n" + stderrTail;
        if (code == 9009 || all.contains("Python was not found")) {
            sb.append("Python was not found. Set 'cytodl-mini environment' to the folder of a conda env / venv where cytodl-mini is installed.");
        } else if (all.contains("No module named 'cytodl_mini'") || all.contains("No module named cytodl_mini")) {
            sb.append("cytodl-mini is not installed in the selected Python environment.\n")
              .append("Install it with:  pip install git+https://github.com/AllenCell/CytoDL-Mini.git");
        } else if (all.contains("No module named 'torch'")) {
            sb.append("PyTorch is not installed in the selected Python environment. See https://pytorch.org/get-started/locally/");
        } else if (all.contains("CUDA out of memory") || all.contains("OutOfMemoryError")) {
            sb.append("The GPU ran out of memory. Try Device = cpu, or crop / downsample the image.");
        } else if (all.contains("Failed to download checkpoint") || all.contains("GitHub CLI")) {
            sb.append("The default checkpoint could not be downloaded. Download megaseg.ckpt manually and set 'Model checkpoint'.");
        } else if (bridgeError != null) {
            sb.append(bridgeError);
        } else {
            sb.append("MegaSeg exited with code ").append(code).append('.');
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
