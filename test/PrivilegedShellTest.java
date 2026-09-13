// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Exercises channel selection and the Shizuku command path against fake
 * hosts, on a development machine with no root, no Shizuku and no device.
 *
 * {@code su} is absent here, so {@link RootShell} always fails to start a
 * session and every case below falls through to the Shizuku branch —
 * which is exactly the panel state this fallback was written for: a
 * rooted device where KS's own UID is not the one holding the grant.
 *
 * The load-bearing assertions are that an ungranted or throwing host is
 * treated as "no Shizuku here" rather than propagating, and that a
 * non-zero exit or a timed-out command is reported as failure rather than
 * as an empty success — this plugin acts on that answer by leaving a
 * panel's ADB port open or closed.
 */
public final class PrivilegedShellTest {
    public static void main(String[] args) throws Exception {
        Class<?> shell = Class.forName("me.jxl.kiosk.plugins.deviceperformance.PrivilegedShell");
        Method attach = method(shell, "attach", PluginHost.class);
        Method detach = method(shell, "detach");
        Method detect = method(shell, "detect");
        Method describe = method(shell, "describe");
        Method available = method(shell, "available");
        Method run = method(shell, "run", String.class, long.class);

        // --- no host at all ---
        detach.invoke(null);
        assertEquals("none", detect.invoke(null), "no host means no channel");
        assertTrue(!(Boolean) available.invoke(null), "no host is not available");
        assertTrue(!(Boolean) run.invoke(null, "true", 1000L), "no channel runs nothing");
        assertEquals("no privileged access", describe.invoke(null), "described as unavailable");

        // --- a host that has Shizuku but has not been granted access ---
        attach.invoke(null, new FakeHost(false, 0, 0, false));
        assertEquals("none", detect.invoke(null), "ungranted Shizuku is not a channel");

        // --- an older host without the capability at all ---
        attach.invoke(null, new ThrowingHost());
        assertEquals("none", detect.invoke(null), "a throwing host is simply no Shizuku");

        // --- granted, running as root ---
        FakeHost root = new FakeHost(true, 0, 0, false);
        attach.invoke(null, root);
        assertEquals("Shizuku", detect.invoke(null), "granted Shizuku is the channel");
        assertEquals("Shizuku (root)", describe.invoke(null), "root helper is called out");
        assertTrue((Boolean) run.invoke(null, "setprop x y", 1000L), "exit 0 is success");
        assertEquals("/system/bin/sh", root.lastCommand[0], "runs through an absolute shell");
        assertEquals("-c", root.lastCommand[1], "as a -c script");
        assertEquals("setprop x y", root.lastCommand[2], "with the script unchanged");

        // --- granted, running as shell: the privilege gap case ---
        attach.invoke(null, new FakeHost(true, 2000, 0, false));
        assertEquals("Shizuku", detect.invoke(null), "shell-mode Shizuku is still a channel");
        assertEquals("Shizuku (shell)", describe.invoke(null), "shell helper is called out");

        // --- a refused property write ---
        attach.invoke(null, new FakeHost(true, 2000, 1, false));
        detect.invoke(null);
        assertTrue(!(Boolean) run.invoke(null, "setprop x y", 1000L), "non-zero exit fails");

        // --- a command that timed out inside the host ---
        attach.invoke(null, new FakeHost(true, 0, 0, true));
        detect.invoke(null);
        assertTrue(!(Boolean) run.invoke(null, "setprop x y", 1000L), "a timeout is not success");

        detach.invoke(null);
        assertEquals("no privileged access", describe.invoke(null), "detach forgets the channel");
        System.out.println("PASS: privileged channel selection — root first, Shizuku fallback, "
            + "root/shell helper distinguished, and refusals and timeouts reported as failure.");
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) throws Exception {
        Method m = owner.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    /** A host answering shizukuState/executeShizuku like KS does. */
    private static final class FakeHost extends StubHost {
        private final boolean granted;
        private final int uid;
        private final int exitCode;
        private final boolean timedOut;
        String[] lastCommand;

        FakeHost(boolean granted, int uid, int exitCode, boolean timedOut) {
            this.granted = granted;
            this.uid = uid;
            this.exitCode = exitCode;
            this.timedOut = timedOut;
        }

        @Override public Map<String, Object> shizukuState() {
            Map<String, Object> state = new HashMap<>();
            state.put("granted", granted);
            state.put("uid", uid);
            return state;
        }

        @Override public void executeShizuku(String[] command, int timeoutMs,
                                             PluginHost.CommandCallback callback) {
            lastCommand = command;
            Map<String, Object> data = new HashMap<>();
            data.put("exitCode", exitCode);
            data.put("timedOut", timedOut);
            data.put("stdout", "");
            // On its own thread, as the real host does, so the latch in
            // PrivilegedShell is genuinely being waited on.
            new Thread(() -> callback.onResult(true, data, null)).start();
        }
    }

    /** A host from before the shizuku capability existed: it inherits
     *  the SDK's default shizukuState(), which throws. */
    private static final class ThrowingHost extends StubHost {}

    /** PluginHost's three non-default methods, unused by this test. */
    private static class StubHost implements PluginHost {
        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    private static void assertTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError("FAIL: " + what);
    }

    private static void assertEquals(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError("FAIL: " + what + " (expected " + expected
                + ", got " + actual + ")");
        }
    }
}
