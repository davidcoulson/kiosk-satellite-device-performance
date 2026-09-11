// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Device-free tests for WebViewEntities.compute, reflected into since the
 *  test lives outside the plugin's package (same convention as the Hello
 *  World template's own test). */
public final class WebViewEntitiesTest {
    public static void main(String[] args) throws Exception {
        Class<?> entities = Class.forName("me.jxl.kiosk.plugins.deviceperformance.WebViewEntities");
        Class<?> entityClass = Class.forName("me.jxl.kiosk.plugins.deviceperformance.WebViewEntities$Entity");
        Method compute = entities.getDeclaredMethod("compute", Double.class, Double.class, Double.class, Integer.class);
        compute.setAccessible(true);
        Field keyField = entityClass.getDeclaredField("key"); keyField.setAccessible(true);
        Field metaField = entityClass.getDeclaredField("metadata"); metaField.setAccessible(true);
        Field stateField = entityClass.getDeclaredField("state"); stateField.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> full = (List<Object>) compute.invoke(null, 37.5, 41.0, 52.0, 3);
        assertEquals(4, full.size(), "always exactly four entities, never conditionally omitted");
        Map<String, Object> byKey = new java.util.LinkedHashMap<>();
        for (Object e : full) byKey.put((String) keyField.get(e), e);

        assertTrue(!byKey.containsKey("cpu_percent"), "no cpu_percent — Kiosk Satellite already publishes this natively");
        assertTrue(!byKey.containsKey("temperature"), "no temperature — Kiosk Satellite already publishes this natively");

        assertEquals(37.5, stateField.get(byKey.get("webview_busy_percent")), "webview busy state");
        @SuppressWarnings("unchecked")
        Map<String, Object> busyMeta = (Map<String, Object>) metaField.get(byKey.get("webview_busy_percent"));
        assertEquals("%", busyMeta.get("unit"), "webview busy unit");
        assertEquals("measurement", busyMeta.get("stateClass"), "webview busy state class");

        assertEquals(41.0, stateField.get(byKey.get("webview_p95_ms_per_s")), "webview p95 state");
        assertEquals(52.0, stateField.get(byKey.get("webview_peak_ms_per_s")), "webview peak state");
        @SuppressWarnings("unchecked")
        Map<String, Object> p95Meta = (Map<String, Object>) metaField.get(byKey.get("webview_p95_ms_per_s"));
        assertEquals("ms/s", p95Meta.get("unit"), "webview p95 unit");

        assertEquals(3.0, stateField.get(byKey.get("renderer_reloads_24h")), "reload count converted to Double");

        // Nothing known yet (first tick, no renderer found): every state is null, but all
        // four entities still publish — a null state is "unknown" in Home Assistant,
        // distinct from the entity not existing at all.
        @SuppressWarnings("unchecked")
        List<Object> empty = (List<Object>) compute.invoke(null, (Object) null, (Object) null, (Object) null, (Object) null);
        assertEquals(4, empty.size(), "still four entities with nothing known yet");
        for (Object e : empty) {
            assertEquals(null, stateField.get(e), "every state is null, not a fabricated zero: " + keyField.get(e));
        }

        System.out.println("PASS: four always-present WebView entities with correct metadata, no CPU%/temperature duplicates, null states represent \"unknown\" not \"missing\".");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!objectsEquals(expected, actual)) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }

    private static boolean objectsEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
