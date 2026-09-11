// SPDX-License-Identifier: Apache-2.0
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.lang.reflect.Method;

/**
 * Device-free tests for TuningMath's package-private static methods,
 * reflected into since the test lives outside the plugin's package (same
 * constraint the Hello World template's own test works under).
 */
public final class TuningMathTest {
    public static void main(String[] args) throws Exception {
        Class<?> math = Class.forName("me.jxl.kiosk.plugins.deviceperformance.TuningMath");

        Method isSafe = math.getDeclaredMethod("isSafeGovernorName", String.class);
        isSafe.setAccessible(true);
        assertTrue((Boolean) isSafe.invoke(null, "performance"), "plain governor name accepted");
        assertTrue((Boolean) isSafe.invoke(null, "schedutil"), "plain governor name accepted");
        assertFalse((Boolean) isSafe.invoke(null, "performance; rm -rf /"), "shell metacharacters rejected");
        assertFalse((Boolean) isSafe.invoke(null, "Performance"), "uppercase rejected");
        assertFalse((Boolean) isSafe.invoke(null, (Object) null), "null rejected");
        assertFalse((Boolean) isSafe.invoke(null, ""), "empty rejected");

        Method resolve = math.getDeclaredMethod("resolveGovernor", String.class, List.class);
        resolve.setAccessible(true);
        List<String> avail = Arrays.asList("interactive", "conservative", "ondemand", "userspace", "powersave", "performance", "schedutil");
        assertEquals("performance", (String) resolve.invoke(null, "Performance", avail), "Performance resolves directly");
        assertEquals("powersave", (String) resolve.invoke(null, "Efficiency", avail), "Efficiency resolves directly");
        assertEquals("schedutil", (String) resolve.invoke(null, "Auto", avail), "Auto prefers schedutil when offered");
        List<String> noSchedutil = Arrays.asList("interactive", "powersave", "performance");
        assertEquals("interactive", (String) resolve.invoke(null, "Auto", noSchedutil), "Auto falls back to interactive without schedutil");
        List<String> onlyUserspace = Collections.singletonList("userspace");
        assertEquals("userspace", (String) resolve.invoke(null, "Performance", onlyUserspace), "unresolvable tier falls back to first available");
        assertNull(resolve.invoke(null, "Auto", Collections.emptyList()), "empty available list resolves to null");

        Method freqFor = math.getDeclaredMethod("freqForPercent", int.class, long.class, long.class);
        freqFor.setAccessible(true);
        assertEquals(1920000L, (long) (Long) freqFor.invoke(null, 100, 408000L, 1920000L), "100% is the hardware max");
        assertEquals(960000L, (long) (Long) freqFor.invoke(null, 50, 408000L, 1920000L), "50% of max");
        assertEquals(408000L, (long) (Long) freqFor.invoke(null, 0, 408000L, 1920000L), "0% clamps up to the hardware min");
        assertEquals(1920000L, (long) (Long) freqFor.invoke(null, 150, 408000L, 1920000L), "out-of-range percent clamps to 100%");
        assertEquals(408000L, (long) (Long) freqFor.invoke(null, -10, 408000L, 1920000L), "negative percent clamps to the hardware min");
        // The real hardware's two clusters this was verified against (rk3576_u): the
        // LITTLE cluster tops out at 1920 MHz, the big cluster at 2112 MHz — the same
        // percent must resolve to a different absolute frequency per cluster.
        assertEquals(1056000L, (long) (Long) freqFor.invoke(null, 50, 408000L, 2112000L), "50% scales per-cluster hardware max");

        Method floorExceeds = math.getDeclaredMethod("floorExceedsCeiling", int.class, int.class, long.class, long.class);
        floorExceeds.setAccessible(true);
        assertFalse((Boolean) floorExceeds.invoke(null, 0, 100, 408000L, 1920000L), "default range never conflicts");
        assertTrue((Boolean) floorExceeds.invoke(null, 75, 25, 408000L, 1920000L), "a floor above the ceiling is detected");

        System.out.println("PASS: governor name safety, tier resolution and fallback, per-cluster frequency-percent math.");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("expected true: " + message);
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError("expected false: " + message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects_equals(expected, actual)) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }

    private static void assertNull(Object actual, String message) {
        if (actual != null) throw new AssertionError(message + " — expected null but got " + actual);
    }

    private static boolean Objects_equals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
