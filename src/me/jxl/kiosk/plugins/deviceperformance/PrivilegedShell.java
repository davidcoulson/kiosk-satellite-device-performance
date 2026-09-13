// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.deviceperformance;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Runs this plugin's privileged commands through whichever channel the
 * panel actually has: a root shell, or Shizuku, or neither.
 *
 * <h2>Why this plugin needs a second channel at all</h2>
 *
 * Direct {@code su} is granted per app UID. Uninstalling and reinstalling
 * Kiosk Satellite gives it a new UID, so a Magisk grant made before the
 * reinstall no longer applies afterwards — the plugin reports "root isn't
 * available" on a panel that is demonstrably rooted, until someone
 * notices and re-grants it. Shizuku's authorization is held by Shizuku
 * against the KS package, so it survives that. On a panel with both, this
 * plugin keeps working through whichever one is currently answering.
 *
 * <h2>Order, and why root comes first</h2>
 *
 * Root wins when both are available: the persistent session in
 * {@link RootShell} costs one grant for the plugin's lifetime and then a
 * write-and-read per command, whereas every Shizuku call is a fresh round
 * trip through a binder to a helper process. Shizuku is the fallback for
 * panels without usable root, not a replacement for it.
 *
 * <h2>What shell can and cannot see</h2>
 *
 * Everything this plugin runs is a read: {@code top}, {@code /proc/stat}
 * and a renderer's {@code /proc/<pid>/stat}. Shell (UID 2000) can read its
 * own and other apps' basic process rows on the panels this targets, so a
 * Shizuku started from ADB is usually enough for the process and WebView
 * sampling. A Shizuku started from a root shell (UID 0) has no gap at all.
 * Where a particular read is refused it comes back as a failed command and
 * the reading goes unknown, which is the same thing that happens on a
 * panel with no privileged channel at all -- never a wrong number.
 *
 * <h2>Shell syntax still works</h2>
 *
 * {@code executeShizuku} takes an absolute executable and an argument
 * array, with no shell interpretation — but {@code /system/bin/sh} is an
 * absolute executable, so passing {@code sh -c <script>} keeps {@code &&}
 * chains working exactly as they do under root. What changes between the
 * two channels is the permissions the commands run with, never the syntax
 * they are written in.
 *
 * <h2>Blocking on an async call</h2>
 *
 * The host answers {@code executeShizuku} on its own worker thread, not
 * the caller's, so waiting on a latch here blocks only this plugin's
 * worker and cannot deadlock against the host. The wait is bounded past
 * the command's own timeout so a host that never answers still returns
 * control instead of pinning the thread.
 */
final class PrivilegedShell {
    private PrivilegedShell() {}

    /** How privileged commands are currently reaching the system. */
    static final String MODE_NONE = "none";
    static final String MODE_ROOT = "root";
    static final String MODE_SHIZUKU = "Shizuku";

    /** Past the command's own timeout, so a host that never calls back
     *  releases the waiting thread rather than holding it forever. */
    static final long HOST_CALLBACK_GRACE_MS = 5000L;

    private static volatile PluginHost host;
    private static volatile String mode = MODE_NONE;
    private static volatile int shizukuUid = -1;

    static void attach(PluginHost pluginHost) {
        host = pluginHost;
    }

    /** Ends the root session and forgets the channel. Called from the
     *  plugin's stop path, after any last commands have run. */
    static void detach() {
        RootShell.shutdown();
        host = null;
        mode = MODE_NONE;
        shizukuUid = -1;
    }

    /** Re-checks which channel is usable. Root first; Shizuku only when
     *  root is absent. Worth calling from the plugin's detect path rather
     *  than once at start: a panel does not gain root while the app is
     *  running, but Shizuku genuinely can be started or authorized
     *  mid-session, which is exactly the case this fallback exists for. */
    static String detect() {
        if (RootShell.isRooted()) {
            mode = MODE_ROOT;
            return mode;
        }
        if (shizukuReady()) {
            mode = MODE_SHIZUKU;
            return mode;
        }
        mode = MODE_NONE;
        return mode;
    }

    static String mode() {
        return mode;
    }

    static boolean available() {
        return !MODE_NONE.equals(mode);
    }

    /** True when Shizuku is the active channel and its helper runs as
     *  root rather than shell — the case with no privilege gap. */
    static boolean shizukuIsRoot() {
        return MODE_SHIZUKU.equals(mode) && shizukuUid == 0;
    }

    /** How the active channel should be described to someone reading the
     *  plugin's status line, including the detail that decides whether a
     *  property write can be expected to work. */
    static String describe() {
        if (MODE_ROOT.equals(mode)) return "root";
        if (MODE_SHIZUKU.equals(mode)) {
            return shizukuIsRoot() ? "Shizuku (root)" : "Shizuku (shell)";
        }
        return "no privileged access";
    }

    private static boolean shizukuReady() {
        PluginHost current = host;
        if (current == null) return false;
        try {
            Map<String, Object> state = current.shizukuState();
            if (state == null || !Boolean.TRUE.equals(state.get("granted"))) return false;
            Object uid = state.get("uid");
            shizukuUid = uid instanceof Number ? ((Number) uid).intValue() : -1;
            return true;
        } catch (Throwable ignored) {
            // An older host without the Shizuku capability throws rather
            // than answering; that is simply "no Shizuku here".
            return false;
        }
    }

    /** Runs [cmd], returning whether it exited 0 within [timeoutMs]. */
    static boolean run(String cmd, long timeoutMs) {
        if (MODE_ROOT.equals(mode)) return RootShell.run(cmd, timeoutMs);
        if (MODE_SHIZUKU.equals(mode)) return shizukuOutput(cmd, timeoutMs) != null;
        return false;
    }

    /** Runs [cmd] and returns trimmed stdout, or null on any failure. */
    static String runOutput(String cmd, long timeoutMs) {
        if (MODE_ROOT.equals(mode)) return RootShell.runOutput(cmd, timeoutMs);
        if (MODE_SHIZUKU.equals(mode)) return shizukuOutput(cmd, timeoutMs);
        return null;
    }

    private static String shizukuOutput(String cmd, long timeoutMs) {
        PluginHost current = host;
        if (current == null) return null;
        final AtomicReference<String> result = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            current.executeShizuku(
                new String[]{"/system/bin/sh", "-c", cmd},
                (int) Math.max(1L, Math.min(timeoutMs, Integer.MAX_VALUE)),
                (ok, data, error) -> {
                    try {
                        if (ok && data instanceof Map) {
                            Map<?, ?> value = (Map<?, ?>) data;
                            Object exit = value.get("exitCode");
                            boolean timedOut = Boolean.TRUE.equals(value.get("timedOut"));
                            boolean succeeded = !timedOut
                                && exit instanceof Number && ((Number) exit).intValue() == 0;
                            if (succeeded) {
                                Object out = value.get("stdout");
                                result.set(out == null ? "" : String.valueOf(out).trim());
                            }
                        }
                    } finally {
                        done.countDown();
                    }
                });
        } catch (Throwable ignored) {
            return null;
        }
        try {
            if (!done.await(timeoutMs + HOST_CALLBACK_GRACE_MS, TimeUnit.MILLISECONDS)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result.get();
    }
}
