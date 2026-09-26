// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Agent-mode detection, and what the plugin publishes because of it, run
 * against fake hosts in simulation mode — no root, no device.
 *
 * The load-bearing assertions are that an agent host never sees a single
 * WebView publication (not even from a first tick that raced the host's
 * answer), and that every host that doesn't say `agent: true` — an older
 * one without the field, a failed command, one that throws — keeps
 * today's full set. CPU/RAM/temp and top processes are unaffected either
 * way, which the status text's "Top CPU:" line stands in for here.
 */
public final class HostModeTest {
    private static final String[] WEBVIEW_SENSORS = {
        "webview_busy_percent", "webview_p95_ms_per_s", "webview_peak_ms_per_s", "renderer_reloads_24h",
    };

    public static void main(String[] args) throws Exception {
        parsing();

        // --- an agent, answering late: the timer must wait for it ---
        FakeHost agent = run(answer(true), 300);
        assertNoWebViewPublished(agent, "agent");
        for (String key : WEBVIEW_SENSORS) {
            assertTrue(agent.calls.contains("removeSensor:" + key), "agent retracts sensor " + key);
        }
        assertTrue(agent.calls.contains("removeTextSensor:webview_verdict"), "agent retracts the verdict text sensor");
        assertTrue(agent.calls.contains("removeSeries:webview_busy"), "agent retracts the chart");
        assertTrue(agent.calls.contains("removeStatusTile:webview_verdict"), "agent retracts the tile");
        assertTrue(!agent.lastStatus.contains("WebView"), "agent status has no WebView line: " + agent.lastStatus);
        assertTrue(agent.lastStatus.contains("CPU: Auto"), "agent status still shows tuning");

        // --- a panel ---
        FakeHost panel = run(answer(false), 0);
        assertWebViewPublished(panel, "panel");
        assertTrue(!panel.calls.contains("removeStatusTile:webview_verdict"), "a panel keeps its tile");

        // --- an older host that answers without the field ---
        Map<String, Object> old = new HashMap<>();
        old.put("apiVersion", 1);
        assertWebViewPublished(run(new Answer(true, old, false), 0), "host without the agent field");

        // --- a host that fails the command, and one that throws ---
        assertWebViewPublished(run(new Answer(false, null, false), 0), "failed getHostApi");
        assertWebViewPublished(run(new Answer(false, null, true), 0), "throwing executeCommand");

        System.out.println("PASS: agent hosts publish no WebView entities, chart or tile and retract them; every other host keeps them.");
    }

    private static void parsing() throws Exception {
        Class<?> mode = Class.forName("me.jxl.kiosk.plugins.deviceperformance.HostMode");
        Method agent = mode.getDeclaredMethod("agent", Object.class);
        agent.setAccessible(true);
        Map<String, Object> api = new HashMap<>();
        assertEquals(false, agent.invoke(null, api), "a missing field is a panel: older hosts omit it");
        api.put("agent", true);
        assertEquals(true, agent.invoke(null, api), "agent: true is an agent");
        api.put("agent", false);
        assertEquals(false, agent.invoke(null, api), "agent: false is a panel");
        api.put("agent", "true");
        assertEquals(false, agent.invoke(null, api), "only a real boolean counts");
        assertEquals(false, agent.invoke(null, (Object) null), "no answer is a panel");
        assertEquals(false, agent.invoke(null, "agent"), "a non-map answer is a panel");
    }

    private static Answer answer(boolean agent) {
        Map<String, Object> api = new HashMap<>();
        api.put("apiVersion", 1);
        api.put("agent", agent);
        return new Answer(true, api, false);
    }

    /** Starts the plugin in simulation mode, waits for its first
     *  diagnostics tick (the status text gains "Top CPU:" only then), and
     *  stops it. */
    private static FakeHost run(Answer answer, long answerDelayMs) throws Exception {
        FakeHost host = new FakeHost(answer, answerDelayMs);
        KioskPlugin plugin = (KioskPlugin) Class.forName(
            "me.jxl.kiosk.plugins.deviceperformance.DevicePerformancePlugin").getDeclaredConstructor().newInstance();
        Map<String, Object> settings = new HashMap<>();
        settings.put("simulation", true);
        plugin.start(host, settings);
        long deadline = System.currentTimeMillis() + 5000;
        while (!host.lastStatus.contains("Top CPU:")) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("no diagnostics tick within 5s");
            Thread.sleep(20);
        }
        plugin.stop();
        return host;
    }

    private static void assertNoWebViewPublished(FakeHost host, String label) {
        for (String call : host.snapshot()) {
            assertTrue(!call.startsWith("publish"), label + " must publish nothing WebView-related, but saw " + call);
        }
    }

    private static void assertWebViewPublished(FakeHost host, String label) {
        List<String> calls = host.snapshot();
        for (String key : WEBVIEW_SENSORS) {
            assertTrue(calls.contains("publishSensor:" + key), label + " publishes sensor " + key);
        }
        assertTrue(calls.contains("publishTextSensor:webview_verdict"), label + " publishes the verdict text sensor");
        assertTrue(calls.contains("publishStatusTile:webview_verdict"), label + " publishes the verdict tile");
        for (String key : WEBVIEW_SENSORS) {
            assertTrue(!calls.contains("removeSensor:" + key), label + " does not retract " + key);
        }
    }

    private static final class Answer {
        final boolean ok;
        final Object data;
        final boolean throwing;
        Answer(boolean ok, Object data, boolean throwing) {
            this.ok = ok;
            this.data = data;
            this.throwing = throwing;
        }
    }

    private static final class FakeHost implements PluginHost {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        volatile String lastStatus = "";
        private final Answer answer;
        private final long answerDelayMs;

        FakeHost(Answer answer, long answerDelayMs) {
            this.answer = answer;
            this.answerDelayMs = answerDelayMs;
        }

        List<String> snapshot() {
            synchronized (calls) { return new ArrayList<>(calls); }
        }

        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            if (!"getHostApi".equals(command)) {
                callback.onResult(false, null, "not in this test");
                return;
            }
            if (answer.throwing) throw new UnsupportedOperationException("SDK 1 required");
            Thread t = new Thread(() -> {
                try { Thread.sleep(answerDelayMs); } catch (InterruptedException ignored) {}
                callback.onResult(answer.ok, answer.ok ? answer.data : null, answer.ok ? null : "failed");
            });
            t.setDaemon(true);
            t.start();
        }
        @Override public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) { calls.add("publishSensor:" + key); }
        @Override public void removeSensor(String key) { calls.add("removeSensor:" + key); }
        @Override public void publishTextSensor(String key, String name, String state) { calls.add("publishTextSensor:" + key); }
        @Override public void removeTextSensor(String key) { calls.add("removeTextSensor:" + key); }
        @Override public void publishSeries(String key, Map<String, Object> chart) { calls.add("publishSeries:" + key); }
        @Override public void removeSeries(String key) { calls.add("removeSeries:" + key); }
        @Override public void publishStatusTile(String key, String title, String level, String text) { calls.add("publishStatusTile:" + key); }
        @Override public void removeStatusTile(String key) { calls.add("removeStatusTile:" + key); }
        @Override public void status(String message, boolean error) { lastStatus = message; }
        @Override public void saveSettings(Map<String, Object> values) {}
        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }
}
