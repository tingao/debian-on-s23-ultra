package com.dsh.autoroot;

import android.content.Context;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Keep;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Runs INSIDE the Shizuku user service process, which executes with the
 * **shell** uid (2000). That is the whole point: the CVE-2026-43499 exploit
 * must write tracing_on under sys/kernel/tracing and files under
 * data/local/tmp, and an app domain may do neither.
 *
 * Staging is done by unzipping our own APK with ZipFile - the APK is world
 * readable, so shell can open it, and this avoids transferring megabytes
 * through a binder transaction.
 */
public class UserService extends IUserService.Stub {

    private static final String TAG = "AutoRootUS";
    private static final String TMP = "/data/local/tmp";

    /**
     * asset name, destination, chmod - only where the destination differs from
     * the asset name.
     *
     * Everything else in assets/ is staged to /data/local/tmp under its own name
     * by extractAll, so dropping a script into the APK is enough to ship it.
     * That fallback exists because this list used to be the whole story, and it
     * silently stopped matching when the exploit payloads were renamed: a clean
     * install would have had no exploit to run, and the only symptom would have
     * been a line in a log nobody reads until something is already broken.
     */
    private static final String[][] ASSETS = {
            // ksud is staged twice on purpose: ksud-selected is what the root flow
            // invokes, and .ksud-stage is what the KernelSU installer picks up.
            {"ksud-selected", TMP + "/ksud-selected", "755"},
            {"ksud-selected", TMP + "/.ksud-stage", "755"},
            {"lockdown.sh", TMP + "/ota_lockdown/lockdown.sh", "755"},
            {"watchdog.sh", TMP + "/ota_lockdown/watchdog.sh", "755"},
            {"status.sh", TMP + "/ota_lockdown/status.sh", "755"},
            {"hosts.block", TMP + "/ota_lockdown/hosts.block", "644"},
            {"debian-launcher.sh", TMP + "/debian", "755"},
    };

    /** Handled elsewhere, or not for the device at all. */
    private static final java.util.List<String> SKIP = java.util.Arrays.asList(
            "shizuku.apk",   // read straight out of the APK when the user taps Install
            "NOTICE.txt");   // attribution, belongs in the app not in /data/local/tmp

    public UserService() {
        Log.i(TAG, "ctor");
    }

    @Keep
    public UserService(Context context) {
        Log.i(TAG, "ctor with context");
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public void exit() {
        destroy();
    }

    @Override
    public int uid() {
        return Os.getuid();
    }

    @Override
    public boolean isRooted() {
        try {
            return run("su -c id 2>&1").contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String run(String cmd) throws RemoteException {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n));
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            sb.append("ERR: ").append(t);
        }
        return sb.toString();
    }

    @Override
    public String extractAll(String apkPath) throws RemoteException {
        StringBuilder report = new StringBuilder();
        File apk = new File(apkPath);
        if (!apk.canRead()) {
            return "ERR: cannot read apk " + apkPath;
        }
        try (ZipFile zf = new ZipFile(apk)) {
            for (String[] spec : ASSETS) {
                ZipEntry e = zf.getEntry("assets/" + spec[0]);
                if (e == null) {
                    report.append("MISSING assets/").append(spec[0]).append('\n');
                    continue;
                }
                stage(zf, e, spec[1], spec[2], report);
            }

            // Everything else in assets/ goes to /data/local/tmp under its own
            // name. Enumerating the APK is what keeps this correct when a script
            // is added or renamed; the explicit list above only covers the few
            // files whose destination differs.
            java.util.Enumeration<? extends ZipEntry> all = zf.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                String name = e.getName();
                if (!name.startsWith("assets/") || name.endsWith("/")) continue;
                String base = name.substring("assets/".length());
                if (base.isEmpty() || base.contains("/")) continue;
                if (SKIP.contains(base)) continue;
                boolean mapped = false;
                for (String[] spec : ASSETS) {
                    if (spec[0].equals(base)) { mapped = true; break; }
                }
                if (mapped) continue;
                stage(zf, e, TMP + "/" + base, "755", report);
            }
        } catch (Throwable t) {
            report.append("ERR ").append(t).append('\n');
        }
        run("chmod 755 " + TMP + "/ota_lockdown 2>/dev/null");
        return report.toString();
    }

    /** Write one asset to its destination, replacing whatever is there. */
    private void stage(ZipFile zf, ZipEntry e, String dest, String mode, StringBuilder report) {
        try {
            File out = new File(dest);
            File parent = out.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            // Remove first: a destination created earlier by ROOT cannot be
            // truncated by this shell-uid process (EACCES), even though the
            // shell-owned parent directory does allow unlinking it.
            run("rm -f '" + dest + "'");
            long total = 0;
            try (InputStream in = zf.getInputStream(e);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    total += n;
                }
                fos.flush();
            }
            run("chmod " + mode + " '" + dest + "'");
            report.append("ok ").append(dest).append(' ').append(total).append('\n');
        } catch (Throwable t) {
            report.append("ERR ").append(dest).append(' ').append(t).append('\n');
        }
    }

    /** Unused helper kept for clarity. */
    @SuppressWarnings("unused")
    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
