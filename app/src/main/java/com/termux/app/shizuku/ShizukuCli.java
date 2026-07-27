package com.termux.app.shizuku;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import moe.shizuku.api.BinderContainer;
import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;

/**
 * Command-line entry point for the built-in Shizuku integration in Termux.
 *
 * <p>This class is launched via {@code app_process} from the {@code shizuku} wrapper
 * script installed in {@code $PREFIX/bin/shizuku}. It uses the Shizuku API client
 * (bundled into the Termux APK) to talk to a running Shizuku server, requesting the
 * server binder through the same broadcast handshake the official {@code rish} tool
 * uses (intent {@code rikka.shizuku.intent.action.REQUEST_BINDER} sent to the
 * Shizuku manager app, which replies by {@code transact(1, ...)} on a receiver
 * binder carrying a {@link BinderContainer}).
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code status}  — print server version, uid, SELinux context, permission state</li>
 *   <li>{@code version} — print API + server versions</li>
 *   <li>{@code start}   — start the Shizuku server on rooted devices via {@code su}</li>
 *   <li>{@code stop}    — call {@code IShizukuService.exit()}</li>
 *   <li>{@code run <cmd> [args...]} — run a command through Shizuku</li>
 *   <li>{@code shell [cmd]}         — interactive shell through Shizuku</li>
 *   <li>{@code help}                — print usage</li>
 * </ul>
 *
 * <p>The Shizuku manager app ({@code moe.shizuku.privileged.api}) must be installed
 * for any subcommand that needs to talk to the server. The first time a subcommand
 * is used, the user must grant Termux permission in the Shizuku manager app.
 */
public final class ShizukuCli {

    private static final String TAG = "ShizukuCli";

    private static final String ACTION_REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";
    private static final String SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    private static final String EXTRA_DATA = "data";
    private static final String EXTRA_RECEIVER = "receiver";
    /** Transaction code used by the manager's ShellBinderRequestHandler to push the binder. */
    private static final int BINDER_RECEIVED_TRANSACTION = 1;
    private static final long BINDER_TIMEOUT_SECONDS = 30;

    private static final String TERMUX_PACKAGE_NAME = "com.termux";

    private static final int EXIT_OK = 0;
    private static final int EXIT_USAGE = 1;
    private static final int EXIT_NO_BINDER = 2;
    private static final int EXIT_NO_PERMISSION = 3;
    private static final int EXIT_NO_MANAGER = 4;
    private static final int EXIT_NOT_ROOTED = 5;
    private static final int EXIT_ERROR = 6;

    private ShizukuCli() {
    }

    public static void main(String[] args) {
        // Prepare the main Looper and then enter Looper.loop() on the main
        // thread. The command logic runs in a worker thread; sendBroadcast()
        // (used by requestBinder()) may post work onto the main thread's
        // message queue, so the Looper must actually be running — otherwise
        // the framework can abort the process with SIGABRT ("Aborted"). When
        // the worker finishes it quits the main Looper, which lets loop()
        // return and the process to exit cleanly.
        Looper.prepareMainLooper();

        final String[] cmdArgs = args;
        final AtomicReference<Integer> exitRef = new AtomicReference<>(EXIT_ERROR);

        Thread worker = new Thread(() -> {
            try {
                exitRef.set(run(cmdArgs));
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                exitRef.set(EXIT_ERROR);
            } finally {
                // Quit the main Looper so Looper.loop() returns and we can exit.
                Looper.getMainLooper().quit();
            }
        }, "shizuku-cmd");
        worker.setDaemon(true);
        worker.start();

        // Process messages on the main thread until the worker quits the Looper.
        Looper.loop();

        // loop() has returned; flush and exit.
        System.out.flush();
        System.err.flush();
        System.exit(exitRef.get());
    }

    private static int run(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp(System.out);
            return EXIT_USAGE;
        }

        String cmd = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);

        switch (cmd) {
            case "help":
            case "-h":
            case "--help":
                printHelp(System.out);
                return EXIT_OK;
            case "version":
            case "--version":
            case "-v":
                return cmdVersion(rest);
            case "status":
                return cmdStatus(rest);
            case "start":
                return cmdStart(rest);
            case "stop":
                return cmdStop(rest);
            case "run":
            case "exec":
                return cmdRun(rest);
            case "shell":
                return cmdShell(rest);
            default:
                System.err.println("Unknown command: " + cmd);
                System.err.println();
                printHelp(System.err);
                return EXIT_USAGE;
        }
    }

    // ---------------------------------------------------------------------
    // Subcommands
    // ---------------------------------------------------------------------

    private static int cmdStatus(String[] args) throws Exception {
        IShizukuService svc = getServiceOrExit(true);
        if (svc == null) {
            return EXIT_NO_BINDER;
        }
        int version = svc.getVersion();
        int uid = svc.getUid();
        String seContext;
        try {
            seContext = svc.getSELinuxContext();
        } catch (Exception e) {
            seContext = "(unavailable: " + e.getMessage() + ")";
        }
        boolean perm;
        try {
            perm = svc.checkSelfPermission();
        } catch (Exception e) {
            perm = false;
        }
        String uidDesc;
        if (uid == 0) {
            uidDesc = uid + " (root)";
        } else if (uid == 2000) {
            uidDesc = uid + " (shell / adb)";
        } else {
            uidDesc = String.valueOf(uid);
        }
        System.out.println("Shizuku is running.");
        System.out.println("  Server version:  " + version);
        System.out.println("  Running as uid:  " + uidDesc);
        System.out.println("  SELinux context: " + seContext);
        System.out.println("  Termux API perm: " + (perm ? "granted" : "not granted"));
        if (!perm) {
            System.out.println();
            System.out.println("Grant Termux permission in the Shizuku manager app");
            System.out.println("before using 'shizuku run' or 'shizuku shell'.");
        }
        return EXIT_OK;
    }

    private static int cmdVersion(String[] args) {
        System.out.println("Termux built-in Shizuku client");
        System.out.println("  API client: 13.1.5");
        IShizukuService svc = null;
        try {
            svc = getServiceOrExit(false);
        } catch (Exception ignored) {
        }
        if (svc == null) {
            System.out.println("  Server:      not running");
        } else {
            try {
                System.out.println("  Server:      " + svc.getVersion());
            } catch (Exception e) {
                System.out.println("  Server:      unknown (" + e.getMessage() + ")");
            }
        }
        return EXIT_OK;
    }

    private static int cmdStart(String[] args) throws Exception {
        String managerApk = findManagerApkPath();
        if (managerApk == null) {
            System.err.println("Shizuku manager app (" + SHIZUKU_MANAGER_PACKAGE + ") is not installed.");
            System.err.println("Install it from https://github.com/RikkaApps/Shizuku/releases first.");
            return EXIT_NO_MANAGER;
        }
        String starter = findStarterBinary(managerApk);
        if (starter == null) {
            System.err.println("Could not locate libshizuku.so starter binary inside the manager app.");
            System.err.println("Manager APK: " + managerApk);
            return EXIT_ERROR;
        }

        if (!isSuAvailable()) {
            System.err.println("Starting Shizuku from Termux requires root (su).");
            System.err.println();
            System.err.println("On non-rooted devices, start Shizuku one of these ways instead:");
            System.err.println("  1. From a computer over USB/wireless ADB:");
            System.err.println("       adb shell " + starter);
            System.err.println("  2. From the Shizuku manager app (wireless ADB, Android 11+).");
            System.err.println();
            System.err.println("Once started, run 'shizuku status' to verify from Termux.");
            return EXIT_NOT_ROOTED;
        }

        String startCmd = starter + " --apk=" + managerApk;
        System.out.println("Starting Shizuku server via su...");
        System.out.println("  Command: su -c " + startCmd);
        ProcessBuilder pb = new ProcessBuilder("su", "-c", startCmd);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            System.err.println("Failed to launch su: " + e.getMessage());
            return EXIT_NOT_ROOTED;
        }
        int code = p.waitFor();
        if (code == 0) {
            System.out.println("Shizuku start command exited successfully.");
            System.out.println("Run 'shizuku status' to verify the server is running.");
            return EXIT_OK;
        }
        System.err.println("su command exited with code " + code + ".");
        return EXIT_ERROR;
    }

    private static int cmdStop(String[] args) throws Exception {
        IShizukuService svc = getServiceOrExit(false);
        if (svc == null) {
            return EXIT_NO_BINDER;
        }
        try {
            svc.exit();
            System.out.println("Shizuku server stop requested.");
            return EXIT_OK;
        } catch (Exception e) {
            System.err.println("Failed to stop Shizuku server: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private static int cmdRun(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: shizuku run <command> [args...]");
            return EXIT_USAGE;
        }
        IShizukuService svc = getServiceOrExit(true);
        if (svc == null) {
            return EXIT_NO_BINDER;
        }
        IRemoteProcess rp;
        try {
            rp = svc.newProcess(args, null, null);
        } catch (Exception e) {
            System.err.println("Failed to start process via Shizuku: " + e.getMessage());
            return EXIT_ERROR;
        }
        return pipeRemoteProcess(rp);
    }

    private static int cmdShell(String[] args) throws Exception {
        IShizukuService svc = getServiceOrExit(true);
        if (svc == null) {
            return EXIT_NO_BINDER;
        }
        String[] cmd;
        if (args.length > 0) {
            cmd = args;
        } else {
            cmd = new String[] { "/system/bin/sh" };
        }
        IRemoteProcess rp;
        try {
            rp = svc.newProcess(cmd, null, null);
        } catch (Exception e) {
            System.err.println("Failed to start shell via Shizuku: " + e.getMessage());
            return EXIT_ERROR;
        }
        return pipeRemoteProcess(rp);
    }

    // ---------------------------------------------------------------------
    // Process I/O piping
    // ---------------------------------------------------------------------

    private static int pipeRemoteProcess(IRemoteProcess rp) throws Exception {
        ParcelFileDescriptor inFd = rp.getOutputStream();
        ParcelFileDescriptor outFd = rp.getInputStream();
        ParcelFileDescriptor errFd = rp.getErrorStream();

        Thread stdinThread = new Thread(() -> {
            try (OutputStream dst = new FileOutputStream(inFd.getFileDescriptor())) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = System.in.read(buf)) != -1) {
                    dst.write(buf, 0, n);
                    dst.flush();
                }
            } catch (IOException ignored) {
                // stdin closed or pipe broken
            }
        }, "shizuku-stdin");

        Thread stdoutThread = new Thread(() -> {
            try (InputStream src = new FileInputStream(outFd.getFileDescriptor())) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = src.read(buf)) != -1) {
                    System.out.write(buf, 0, n);
                    System.out.flush();
                }
            } catch (IOException ignored) {
                // stdout pipe closed
            }
        }, "shizuku-stdout");

        Thread stderrThread = new Thread(() -> {
            try (InputStream src = new FileInputStream(errFd.getFileDescriptor())) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = src.read(buf)) != -1) {
                    System.err.write(buf, 0, n);
                    System.err.flush();
                }
            } catch (IOException ignored) {
                // stderr pipe closed
            }
        }, "shizuku-stderr");

        stdinThread.setDaemon(true);
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();
        stdinThread.start();

        int code;
        try {
            code = rp.waitFor();
        } catch (Exception e) {
            code = -1;
        }

        // Give the pump threads a brief chance to flush remaining output.
        stdoutThread.join(2000);
        stderrThread.join(2000);
        stdinThread.interrupt();
        System.out.flush();
        System.err.flush();
        try {
            rp.destroy();
        } catch (Exception ignored) {
        }
        return code;
    }

    // ---------------------------------------------------------------------
    // Shizuku binder acquisition
    // ---------------------------------------------------------------------

    /**
     * Request the Shizuku server binder via the broadcast handshake, wrap it as an
     * {@link IShizukuService}, and (when {@code requirePermission} is true) check
     * that Termux has been granted the API permission. Returns {@code null} (and
     * prints a diagnostic) if the server is not reachable or permission is missing.
     */
    private static IShizukuService getServiceOrExit(boolean requirePermission) throws Exception {
        IBinder binder = requestBinder();
        if (binder == null || !binder.pingBinder()) {
            System.err.println("Shizuku is not running.");
            if (isSuAvailable()) {
                System.err.println("Start it with: shizuku start");
            } else {
                System.err.println("Start it from the Shizuku manager app or via ADB:");
                System.err.println("  adb shell <libshizuku.so path inside manager apk>");
            }
            return null;
        }
        IShizukuService svc = IShizukuService.Stub.asInterface(binder);
        if (requirePermission) {
            boolean granted;
            try {
                granted = svc.checkSelfPermission();
            } catch (Exception e) {
                System.err.println("Failed to check Shizuku permission: " + e.getMessage());
                return null;
            }
            if (!granted) {
                System.err.println("Termux has not been granted permission to use Shizuku.");
                System.err.println("Open the Shizuku manager app and grant permission to Termux,");
                System.err.println("then re-run this command.");
                return null;
            }
        }
        return svc;
    }

    private static IBinder requestBinder() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<IBinder> result = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();

        // Binder receiver: the Shizuku manager replies with transact(1, ...) carrying
        // a Bundle whose "data" extra is a BinderContainer holding the server binder.
        Binder receiver = new Binder("rikka.shizuku.shell") {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code == BINDER_RECEIVED_TRANSACTION) {
                    try {
                        Bundle bundle = new Bundle(BinderContainer.class.getClassLoader());
                        bundle.readFromParcel(data);
                        BinderContainer container = bundle.getParcelable(EXTRA_DATA);
                        if (container != null && container.binder != null) {
                            result.set(container.binder);
                        } else {
                            // Fallback: some Shizuku builds may write the binder directly.
                            data.setDataPosition(0);
                            try {
                                IBinder direct = data.readStrongBinder();
                                if (direct != null) {
                                    result.set(direct);
                                } else {
                                    error.set("BinderContainer was null");
                                }
                            } catch (Exception ignored) {
                                error.set("BinderContainer was null");
                            }
                        }
                    } catch (Exception e) {
                        error.set("Failed to read binder: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                }
                return true;
            }
        };

        Bundle data = new Bundle();
        data.putBinder(EXTRA_RECEIVER, receiver);

        Intent intent = new Intent(ACTION_REQUEST_BINDER)
                .setPackage(SHIZUKU_MANAGER_PACKAGE)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra(EXTRA_DATA, data);

        Context ctx = getSystemContext();
        if (ctx == null) {
            throw new IllegalStateException("Failed to obtain a Context for broadcasting");
        }
        ctx.sendBroadcast(intent);

        if (!latch.await(BINDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return null;
        }
        if (error.get() != null) {
            System.err.println("Shizuku binder error: " + error.get());
            return null;
        }
        return result.get();
    }

    /**
     * Bootstrap a {@link Context} for use inside a non-app {@code app_process} process
     * by reflecting into {@code ActivityThread.systemMain()}. This is the same pattern
     * used by the official {@code rish} loader.
     */
    private static Context getSystemContext() throws Exception {
        Class<?> atCls = Class.forName("android.app.ActivityThread");
        Method systemMain = atCls.getMethod("systemMain");
        Object thread = systemMain.invoke(null);
        Method getSystemContext = atCls.getMethod("getSystemContext");
        return (Context) getSystemContext.invoke(thread);
    }

    // ---------------------------------------------------------------------
    // Manager APK / starter binary / su discovery
    // ---------------------------------------------------------------------

    private static String findManagerApkPath() {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "pm", "path", SHIZUKU_MANAGER_PACKAGE });
            String out = readAllString(p.getInputStream());
            p.waitFor();
            // Output looks like: package:/data/app/.../base.apk
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.startsWith("package:")) {
                    return line.substring("package:".length()).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String findStarterBinary(String apkPath) {
        if (apkPath == null) return null;
        File apkFile = new File(apkPath);
        File parent = apkFile.getParentFile();
        if (parent == null) return null;
        // Native libs for an installed package are at /data/app/<pkg>/lib/<abi>/
        for (String abi : Build.SUPPORTED_ABIS) {
            File candidate = new File(parent, "lib/" + abi + "/libshizuku.so");
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        // Fall back to a sibling lib directory without the abi qualifier.
        File libRoot = new File(parent, "lib");
        if (libRoot.isDirectory()) {
            File[] abis = libRoot.listFiles();
            if (abis != null) {
                for (File abiDir : abis) {
                    File candidate = new File(abiDir, "libshizuku.so");
                    if (candidate.exists()) {
                        return candidate.getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSuAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "sh", "-c", "command -v su" });
            String out = readAllString(p.getInputStream());
            p.waitFor();
            return out != null && out.trim().length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readAllString(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString();
    }

    // ---------------------------------------------------------------------
    // Help
    // ---------------------------------------------------------------------

    private static void printHelp(PrintStream out) {
        out.println("Usage: shizuku <command> [args...]");
        out.println();
        out.println("Built-in Shizuku integration for Termux. Lets you start, stop, and use");
        out.println("the Shizuku server (running with root or adb/shell privileges) from");
        out.println("inside the Termux shell.");
        out.println();
        out.println("Commands:");
        out.println("  status              Show Shizuku server status (version, uid, context, perm)");
        out.println("  version             Show Shizuku API client and server versions");
        out.println("  start               Start the Shizuku server (rooted devices only)");
        out.println("  stop                Stop the running Shizuku server");
        out.println("  run <cmd> [args...] Run a command through Shizuku");
        out.println("  shell [cmd]         Open an interactive shell through Shizuku");
        out.println("  help                Show this help message");
        out.println();
        out.println("Notes:");
        out.println("  - The Shizuku manager app (moe.shizuku.privileged.api) must be installed");
        out.println("    for any subcommand that talks to the server. Install it from");
        out.println("    https://github.com/RikkaApps/Shizuku/releases");
        out.println("  - On first use, open the Shizuku manager app and grant Termux permission");
        out.println("    to use Shizuku.");
        out.println("  - On non-rooted devices, start Shizuku via ADB or the manager app's");
        out.println("    wireless ADB feature (Android 11+), then use 'shizuku status' here.");
        out.println();
        out.println("Examples:");
        out.println("  shizuku status");
        out.println("  shizuku run pm list packages");
        out.println("  shizuku shell");
    }

}
