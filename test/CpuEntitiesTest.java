// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Device-free tests for CpuEntities.compute, reflected into since the
 *  test lives outside the plugin's package (same convention as the Hello
 *  World template's own test). */
public final class CpuEntitiesTest {
    public static void main(String[] args) throws Exception {
        Class<?> entities = Class.forName("me.jxl.kiosk.plugins.cputuning.CpuEntities");
        Class<?> entityClass = Class.forName("me.jxl.kiosk.plugins.cputuning.CpuEntities$Entity");
        Method compute = entities.getDeclaredMethod("compute",
            Double.class, Double.class, Double.class, Double.class, Double.class, Integer.class);
        compute.setAccessible(true);
        Field keyField = entityClass.getDeclaredField("key"); keyField.setAccessible(true);
        Field metaField = entityClass.getDeclaredField("metadata"); metaField.setAccessible(true);
        Field stateField = entityClass.getDeclaredField("state"); stateField.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Object> full = (List<Object>) compute.invoke(null, 22.0, 41.5, 37.5, 41.0, 52.0, 3);
        assertEquals(6, full.size(), "always exactly six entities, never conditionally omitted");
        Map<String, Object> byKey = new java.util.LinkedHashMap<>();
        for (Object e : full) byKey.put((String) keyField.get(e), e);

        assertEquals(22.0, stateField.get(byKey.get("cpu_percent")), "cpu percent state");
        @SuppressWarnings("unchecked")
        Map<String, Object> cpuMeta = (Map<String, Object>) metaField.get(byKey.get("cpu_percent"));
        assertEquals("%", cpuMeta.get("unit"), "cpu percent unit");
        assertEquals("measurement", cpuMeta.get("stateClass"), "cpu percent state class");

        assertEquals(41.5, stateField.get(byKey.get("temperature")), "temperature state");
        @SuppressWarnings("unchecked")
        Map<String, Object> tempMeta = (Map<String, Object>) metaField.get(byKey.get("temperature"));
        assertEquals("temperature", tempMeta.get("deviceClass"), "temperature device class");
        assertEquals("°C", tempMeta.get("unit"), "temperature unit");

        assertEquals(37.5, stateField.get(byKey.get("webview_busy_percent")), "webview busy state");
        assertEquals(41.0, stateField.get(byKey.get("webview_p95_ms_per_s")), "webview p95 state");
        assertEquals(52.0, stateField.get(byKey.get("webview_peak_ms_per_s")), "webview peak state");
        assertEquals(3.0, stateField.get(byKey.get("renderer_reloads_24h")), "reload count converted to Double");

        // Nothing known yet (first tick, no renderer found, getStats not answered): every
        // state is null, but all six entities still publish — a null state is "unknown"
        // in Home Assistant, distinct from the entity not existing at all.
        @SuppressWarnings("unchecked")
        List<Object> empty = (List<Object>) compute.invoke(null,
            (Object) null, (Object) null, (Object) null, (Object) null, (Object) null, (Object) null);
        assertEquals(6, empty.size(), "still six entities with nothing known yet");
        for (Object e : empty) {
            assertEquals(null, stateField.get(e), "every state is null, not a fabricated zero: " + keyField.get(e));
        }

        System.out.println("PASS: six always-present entities with correct metadata, null states represent \"unknown\" not \"missing\".");
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
