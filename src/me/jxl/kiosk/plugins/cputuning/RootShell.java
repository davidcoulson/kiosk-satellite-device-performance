// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

import java.util.concurrent.TimeUnit;

/**
 * Plain `su -c` command execution — no persistent shell process, unlike
 * the LED plugin's root-helper transport. Governor/frequency/battery-saver
 * changes are rare, user-triggered settings writes, not a per-frame
 * hot path, so the overhead of spawning `su` per call is irrelevant and
 * a fresh process per command is simpler and more robust than keeping
 * one alive across the plugin's whole lifetime.
 */
final class RootShell {
    private RootShell() {}

    static final long DETECT_TIMEOUT_MS = 4000L;
    static final long COMMAND_TIMEOUT_MS = 3000L;

    /** True once `su -c id` succeeds — the same shallow-but-standard probe
     *  used throughout Kiosk Satellite's own root-gated features (LED,
     *  reboot): a shell that can run `su -c` anything is what every
     *  command below actually depends on. */
    static boolean isRooted() {
        return run("id", DETECT_TIMEOUT_MS);
    }

    /** Runs [cmd] as root, returning whether it exited 0 within
     *  [timeoutMs]. A timeout force-kills the process and reports
     *  failure rather than leaving it to finish unobserved. */
    static boolean run(String cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Runs [cmd] as root and returns trimmed stdout, or null on any
     *  failure (non-zero exit, timeout, or a process-launch error). */
    static String runOutput(String cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) out.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            }
            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out.toString().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
