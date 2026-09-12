// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Exercises the persistent root shell against {@code sh} rather than
 * {@code su}, which is why Session takes its shell command as an argument.
 * Everything that matters here — the sentinel framing, exit-code capture,
 * output accumulation, timeout handling and session recovery — is
 * shell-agnostic, so a plain {@code sh} tests all of it on a development
 * machine with no root and no device.
 *
 * The load-bearing test is {@code one process across many commands}: the
 * entire point of the change is that Magisk grants root per process, so a
 * regression to per-command spawning would restore twelve grants a minute
 * and it would only be noticed as toast spam on a panel.
 */
public final class RootShellSessionTest {
    public static void main(String[] args) throws Exception {
        Class<?> sessionClass = Class.forName(
            "me.jxl.kiosk.plugins.deviceperformance.RootShell$Session");
        Method start = sessionClass.getDeclaredMethod("start", String[].class);
        start.setAccessible(true);
        Method run = sessionClass.getDeclaredMethod("run", String.class, long.class);
        run.setAccessible(true);
        Method alive = sessionClass.getDeclaredMethod("alive");
        alive.setAccessible(true);
        Method close = sessionClass.getDeclaredMethod("close");
        close.setAccessible(true);

        Object session = start.invoke(null, (Object) new String[]{"sh"});
        assertTrue(session != null, "a session starts against sh");
        assertTrue((Boolean) alive.invoke(session), "a fresh session is alive");

        // --- the point of the exercise ---
        // Magisk grants per process. If these two commands report different
        // PIDs, every command is spawning its own shell again and the toast
        // storm is back.
        String firstPid = output(run.invoke(session, "echo $$", 3000L));
        String secondPid = output(run.invoke(session, "echo $$", 3000L));
        assertTrue(!firstPid.isEmpty(), "the shell reports its PID");
        assertEquals(firstPid, secondPid,
            "every command runs in ONE shell process — differing PIDs mean per-command su spawning is back");

        // --- framing and exit codes ---
        Object ok = run.invoke(session, "echo hello", 3000L);
        assertTrue(ok(ok), "a successful command reports success");
        assertEquals("hello", output(ok), "stdout comes back trimmed");

        Object failed = run.invoke(session, "false", 3000L);
        assertTrue(!ok(failed), "a non-zero exit reports failure");

        // `exit` would end a persistent shell outright, silently costing
        // root for the plugin's whole life. The subshell wrapper contains
        // it: the command still reports its status, and the session lives.
        Object suicidal = run.invoke(session, "exit 7", 3000L);
        assertTrue(!ok(suicidal), "an explicit non-zero exit reports failure");
        assertTrue(!broken(suicidal), "and does not break the session");
        assertTrue((Boolean) alive.invoke(session), "the shell survived a command containing exit");
        assertEquals("still here", output(run.invoke(session, "echo still here", 3000L)),
            "the session keeps working after a command tried to exit it");

        Object multi = run.invoke(session, "echo one\necho two", 3000L);
        assertTrue(ok(multi), "a multi-line script runs");
        assertEquals("one\ntwo", output(multi), "every line of output is kept, in order");

        // A script's status is its last command's, matching what
        // `su -c script` used to report.
        assertTrue(!ok(run.invoke(session, "true\nfalse", 3000L)),
            "a script reports its last command's status");
        assertTrue(ok(run.invoke(session, "false\ntrue", 3000L)),
            "an early failure does not fail the script if the last command succeeds");

        // Output that merely resembles the sentinel must not end the read
        // early. The token is per-session and random, so this is the
        // closest an attacker or accident can plausibly get.
        Object nearMiss = run.invoke(session, "echo __KSROOT_notthetoken__:0\necho real", 3000L);
        assertTrue(ok(nearMiss), "a near-miss sentinel does not break framing");
        assertEquals("__KSROOT_notthetoken__:0\nreal", output(nearMiss),
            "a line resembling the sentinel is treated as ordinary output");

        // stderr is merged, same as the old redirectErrorStream(true).
        Object stderr = run.invoke(session, "echo oops 1>&2", 3000L);
        assertTrue(ok(stderr), "writing to stderr alone still exits 0");
        assertEquals("oops", output(stderr), "stderr is captured alongside stdout");

        assertTrue((Boolean) alive.invoke(session), "the session survived all of that");

        // --- timeout kills the session rather than desyncing it ---
        Object slow = run.invoke(session, "sleep 5", 200L);
        assertTrue(!ok(slow), "a command over its timeout fails");
        assertTrue(broken(slow),
            "a timeout marks the session broken — its late output would corrupt the next read");

        close.invoke(session);
        assertTrue(!(Boolean) alive.invoke(session), "a closed session is not alive");
        Object afterClose = run.invoke(session, "echo x", 1000L);
        assertTrue(!ok(afterClose) && broken(afterClose),
            "a closed session refuses work and reports itself broken rather than hanging");

        // --- a dead shell is detected, not hung on ---
        // `exit` can no longer kill the session (the subshell contains it),
        // so this kills the shell process outright. $$ is the session
        // shell's PID even from inside the subshell, which is exactly the
        // case that matters in production: Magisk revoking root, or the
        // shell being killed under memory pressure on a small panel.
        Object dying = start.invoke(null, (Object) new String[]{"sh"});
        run.invoke(dying, "kill -9 $$", 3000L);
        Thread.sleep(300);
        Object afterExit = run.invoke(dying, "echo x", 1000L);
        assertTrue(!ok(afterExit) && broken(afterExit),
            "a shell that exited is reported broken so the caller starts a fresh one");
        close.invoke(dying);

        System.out.println("PASS: persistent root shell — one process across many commands, "
            + "sentinel framing with near-miss safety, exit codes, multi-line scripts, merged stderr, "
            + "timeout and dead-shell recovery.");
    }

    private static boolean ok(Object result) throws Exception {
        return (Boolean) field(result, "ok");
    }

    private static boolean broken(Object result) throws Exception {
        return (Boolean) field(result, "sessionBroken");
    }

    private static String output(Object result) throws Exception {
        return (String) field(result, "output");
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!(expected == null ? actual == null : expected.equals(actual))) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }
}
