// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Device-free tests for WebViewPerfMath's package-private static
 *  methods, reflected into since this test lives outside the plugin's
 *  package (same convention as the Hello World template's own test). */
public final class WebViewPerfMathTest {
    public static void main(String[] args) throws Exception {
        Class<?> math = Class.forName("me.jxl.kiosk.plugins.cputuning.WebViewPerfMath");

        Method statFields = math.getDeclaredMethod("statFieldsAfterComm", String.class);
        statFields.setAccessible(true);
        // A real-shaped CrRendererMain thread stat line: comm can itself contain a
        // space and even a stray ")" (the kernel permits arbitrary bytes, truncated
        // to 16 chars) — the split point must be the LAST ")", never the first.
        String weirdComm = "12345 (Cr)enderer M) S 1 1 1 0 -1 4194624 100 0 0 0 55 12 0 0 20 0 4 0 "
            + "1000 500000 200 18446744073709551615 1 1 0 0 0 0 0 0 0 0 0 0 17 3 0 0 0 0 0";
        String[] fields = (String[]) statFields.invoke(null, weirdComm);
        assertNotNull(fields, "a line with a parenthesis inside comm still parses");
        assertEquals("S", fields[0], "field 0 is state, sliced after the LAST ')'");
        assertEquals("55", fields[11], "field 11 is utime");
        assertEquals("12", fields[12], "field 12 is stime");

        assertNull(statFields.invoke(null, (Object) null), "null input yields null");
        assertNull(statFields.invoke(null, "no closing paren here"), "a line with no ')' yields null");
        assertNull(statFields.invoke(null, "1 (comm)"), "nothing after the comm field yields null");

        Method parseRow = math.getDeclaredMethod("parseRenderRow", String.class);
        parseRow.setAccessible(true);
        String row = "777 " + weirdComm;
        @SuppressWarnings("unchecked")
        Map.Entry<Integer, Long> entry = (Map.Entry<Integer, Long>) parseRow.invoke(null, row);
        assertNotNull(entry, "a well-formed \"pid stat...\" row parses");
        assertEquals(777, entry.getKey(), "pid parsed from before the first space");
        assertEquals(67L, (long) entry.getValue(), "utime+stime = 55+12");

        assertNull(parseRow.invoke(null, (Object) null), "null row yields null");
        assertNull(parseRow.invoke(null, "not-a-pid " + weirdComm), "an unparseable pid yields null");
        assertNull(parseRow.invoke(null, "777 (comm)"), "a row with a truncated stat line yields null");

        Method parseRows = math.getDeclaredMethod("parseRenderRows", String.class);
        parseRows.setAccessible(true);
        String twoRows = row + "\n888 " + weirdComm + "\ngarbage line\n";
        @SuppressWarnings("unchecked")
        Map<Integer, Long> parsed = (Map<Integer, Long>) parseRows.invoke(null, twoRows);
        assertEquals(2, parsed.size(), "two well-formed rows parsed, one malformed line skipped, not fatal");
        assertEquals(67L, (long) parsed.get(777), "first pid's jiffies");
        assertEquals(67L, (long) parsed.get(888), "second pid's jiffies");

        @SuppressWarnings("unchecked")
        Map<Integer, Long> emptyParsed = (Map<Integer, Long>) parseRows.invoke(null, (Object) null);
        assertTrue(emptyParsed.isEmpty(), "null probe output parses to an empty map, not a crash");

        Method busiest = math.getDeclaredMethod("busiestMainPct", Map.class, Map.class, double.class);
        busiest.setAccessible(true);
        Map<Integer, Long> prev = new HashMap<>();
        prev.put(1, 1000L);
        Map<Integer, Long> cur = new HashMap<>();
        cur.put(1, 1050L); // 50 jiffies over 1s == 50% of one core at HZ=100
        assertEquals(50.0, (double) busiest.invoke(null, prev, cur, 1.0), "one renderer thread's simple delta");

        Map<Integer, Long> curTwo = new HashMap<>();
        curTwo.put(1, 1020L); // 20%
        curTwo.put(2, 1090L); // no baseline for pid 2 — must be ignored, not treated as 0-based
        Map<Integer, Long> prevTwo = new HashMap<>();
        prevTwo.put(1, 1000L);
        assertEquals(20.0, (double) busiest.invoke(null, prevTwo, curTwo, 1.0),
            "a thread with no prior baseline (a renderer that just started) never contributes a delta");

        assertEquals(-1.0, (double) busiest.invoke(null, new HashMap<Integer, Long>(), cur, 1.0),
            "no prior sample at all (first tick) reports -1, not a bogus 0%");
        assertEquals(-1.0, (double) busiest.invoke(null, prev, new HashMap<Integer, Long>(), 1.0),
            "no current renderer at all reports -1 (\"no renderer\"), not 0%");
        assertEquals(-1.0, (double) busiest.invoke(null, prev, cur, -1.0),
            "a non-positive elapsed time (clock oddity) refuses to divide, reports -1");

        Method verdict = math.getDeclaredMethod("verdict", double.class);
        verdict.setAccessible(true);
        assertEquals("smooth", verdict.invoke(null, 0.0), "0% is smooth");
        assertEquals("smooth", verdict.invoke(null, 49.9), "just under the smooth boundary");
        assertEquals("occasional", verdict.invoke(null, 50.0), "the smooth/occasional boundary is occasional");
        assertEquals("occasional", verdict.invoke(null, 84.9), "just under the occasional boundary");
        assertEquals("janky", verdict.invoke(null, 85.0), "the occasional/janky boundary is janky");
        assertEquals("janky", verdict.invoke(null, 100.0), "fully saturated is janky");

        Method clamp = math.getDeclaredMethod("clampPct", double.class);
        clamp.setAccessible(true);
        assertEquals(0.0, (double) clamp.invoke(null, -5.0), "negative clamps to 0");
        assertEquals(100.0, (double) clamp.invoke(null, 150.0), "over 100 clamps to 100");
        assertEquals(42.0, (double) clamp.invoke(null, 42.0), "an in-range value passes through");

        System.out.println("PASS: /proc/stat comm-parenthesis parsing, render-row parsing, jiffy-delta math, verdict thresholds.");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("expected true: " + message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!objectsEquals(expected, actual)) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }

    private static void assertNull(Object actual, String message) {
        if (actual != null) throw new AssertionError(message + " — expected null but got " + actual);
    }

    private static void assertNotNull(Object actual, String message) {
        if (actual == null) throw new AssertionError(message + " — expected non-null");
    }

    private static boolean objectsEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
