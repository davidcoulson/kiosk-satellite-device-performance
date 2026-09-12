// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.deviceperformance;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Root command execution over a single long-lived {@code su} shell rather
 * than a fresh process per command.
 *
 * <h2>Why persistent</h2>
 *
 * This plugin samples every 10 seconds and each tick used to spawn two
 * {@code su} processes (renderer sampling, then {@code top}). Magisk shows
 * its "granted Superuser rights" toast per <em>request</em>, so that was
 * twelve grants a minute from this plugin alone — enough that Android
 * started dropping the toasts for exceeding its quota, which is how the
 * problem was noticed. Process spawning also is not free on the low-end
 * panels this plugin exists to measure, and measuring a panel should not
 * be a meaningful part of that panel's load.
 *
 * One shell means one grant per plugin start, and per-command cost drops
 * to writing a line and reading the reply.
 *
 * <h2>How a command is framed</h2>
 *
 * Commands go to the shell's stdin; there is no separate process to wait
 * on and therefore no exit status to read. Instead each command is
 * followed by {@code echo <token>:$?}, and output is read until that token
 * appears. The token is generated per session, so command output cannot
 * plausibly contain it. A multi-line script reports its last command's
 * status, exactly as {@code su -c script} did.
 *
 * <h2>What happens when it goes wrong</h2>
 *
 * A command that exceeds its timeout leaves the shell with unread output
 * still to come, and there is no reliable way to tell that output from the
 * next command's. Resynchronising is guesswork, so the session is killed
 * instead and the next call starts a clean one. Same for EOF (the shell
 * died, or root was revoked). Failures are therefore never silent
 * corruption; they cost one extra grant.
 *
 * <h2>On holding root open</h2>
 *
 * A long-lived root shell is a deliberate posture change from transient
 * ones: the privilege is identical, but it stays available for the
 * plugin's lifetime rather than for the moment a command runs. It ends
 * with the plugin — {@link #shutdown()} is called from the plugin's stop
 * path — so disabling the plugin closes it. ha-paneld makes the same
 * trade with its root helper daemon.
 */
final class RootShell {
    private RootShell() {}

    static final long DETECT_TIMEOUT_MS = 4000L;
    static final long COMMAND_TIMEOUT_MS = 3000L;

    private static final Object LOCK = new Object();
    private static Session session;

    /** True once `id` succeeds through the root shell — the same
     *  shallow-but-standard probe used throughout Kiosk Satellite's own
     *  root-gated features: a shell that can run anything as root is what
     *  every command below actually depends on. */
    static boolean isRooted() {
        return run("id", DETECT_TIMEOUT_MS);
    }

    /** Runs [cmd] as root, returning whether it exited 0 within [timeoutMs]. */
    static boolean run(String cmd, long timeoutMs) {
        return exec(cmd, timeoutMs).ok;
    }

    /** Runs [cmd] as root and returns trimmed stdout, or null on any
     *  failure (non-zero exit, timeout, or no usable root shell). */
    static String runOutput(String cmd, long timeoutMs) {
        Result result = exec(cmd, timeoutMs);
        return result.ok ? result.output : null;
    }

    private static Result exec(String cmd, long timeoutMs) {
        synchronized (LOCK) {
            if (session != null && !session.alive()) {
                session.close();
                session = null;
            }
            if (session == null) {
                session = Session.start("su");
                if (session == null) return Result.FAILED;
            }
            Result result = session.run(cmd, timeoutMs);
            if (result.sessionBroken) {
                session.close();
                session = null;
            }
            return result;
        }
    }

    /** Ends the root shell. Safe to call when none is open. */
    static void shutdown() {
        synchronized (LOCK) {
            if (session != null) {
                session.close();
                session = null;
            }
        }
    }

    static final class Result {
        static final Result FAILED = new Result(false, "", false);

        final boolean ok;
        final String output;
        final boolean sessionBroken;

        Result(boolean ok, String output, boolean sessionBroken) {
            this.ok = ok;
            this.output = output;
            this.sessionBroken = sessionBroken;
        }
    }

    /**
     * One shell process and the machinery to talk to it. Package-private
     * and constructible with an arbitrary shell so the framing, timeout
     * and recovery behaviour can be tested against {@code sh} on a
     * development machine, where {@code su} does not exist. Production
     * only ever passes "su".
     */
    static final class Session implements Closeable {
        private final Process process;
        private final BufferedWriter stdin;
        private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(4096);
        private final Thread reader;
        private final String token;
        private volatile boolean eof;

        static Session start(String... command) {
            try {
                Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
                return new Session(p);
            } catch (Exception e) {
                return null;
            }
        }

        private Session(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
            this.token = "__KSROOT_" + Long.toHexString(System.nanoTime())
                + Integer.toHexString(new java.util.Random().nextInt()) + "__";
            this.reader = new Thread(this::pump, "root-shell-reader");
            this.reader.setDaemon(true);
            this.reader.start();
        }

        /** Drains the shell's output into {@link #lines}. A bounded queue
         *  means a command producing more than the queue holds blocks this
         *  thread rather than growing without limit; the consumer is
         *  draining concurrently, so it only stalls output that nobody is
         *  reading, which the timeout then handles. */
        private void pump() {
            try (InputStream in = process.getInputStream()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = br.readLine()) != null) lines.put(line);
            } catch (Exception ignored) {
                // Falls through to EOF: the session is finished either way.
            } finally {
                eof = true;
            }
        }

        boolean alive() {
            return !eof && process.isAlive();
        }

        Result run(String cmd, long timeoutMs) {
            if (!alive()) return new Result(false, "", true);
            lines.clear();   // nothing should be pending; make sure of it
            try {
                // Wrapped in a subshell so a command containing `exit`
                // ends that subshell rather than the session. With a
                // throwaway `su -c` process this could not arise; with a
                // persistent shell an unguarded `exit` would silently take
                // root away for the rest of the plugin's life. $? after
                // the subshell is the subshell's status, so exit codes
                // still read exactly as before.
                stdin.write("( ");
                stdin.write(cmd);
                stdin.write("\n)");
                stdin.write("\n");
                stdin.write("echo " + token + ":$?");
                stdin.write("\n");
                stdin.flush();
            } catch (Exception e) {
                return new Result(false, "", true);
            }

            StringBuilder out = new StringBuilder();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return new Result(false, "", true);
                String line;
                try {
                    line = lines.poll(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Result(false, "", true);
                }
                if (line == null) {
                    // Timed out with the command still running: its output
                    // would desync the next read, so the session goes.
                    return new Result(false, "", true);
                }
                if (line.startsWith(token + ":")) {
                    int exit;
                    try {
                        exit = Integer.parseInt(line.substring(token.length() + 1).trim());
                    } catch (NumberFormatException e) {
                        return new Result(false, "", true);
                    }
                    return new Result(exit == 0, out.toString().trim(), false);
                }
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
        }

        public void close() {
            // Set before killing: destroyForcibly() is asynchronous, so
            // process.isAlive() can still report true for a moment after
            // it returns. This flag is the authoritative "finished" signal
            // and makes alive() false immediately.
            eof = true;
            try {
                stdin.close();
            } catch (Exception ignored) {
                // Closing stdin is the polite exit; the kill below is the
                // guarantee.
            }
            process.destroyForcibly();
            reader.interrupt();
        }
    }
}
