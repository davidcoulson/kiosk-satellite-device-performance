// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Device-free tests for TopProcessMath's package-private static
 *  methods, reflected into since this test lives outside the plugin's
 *  package (same convention as the Hello World template's own test). */
public final class TopProcessMathTest {
    public static void main(String[] args) throws Exception {
        Class<?> math = Class.forName("me.jxl.kiosk.plugins.deviceperformance.TopProcessMath");

        // A real-shaped /proc/stat "cpu " line: user nice system idle iowait irq
        // softirq steal guest guest_nice — ten fields, summed for total jiffies.
        String statPart = "cpu  1000 50 500 8000 100 10 5 0 0 0\ncpu0 500 25 250 4000 50 5 2 0 0 0\n";
        // Two process /proc/<pid>/stat lines: fields after "(comm) " are
        // state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt
        // cmajflt utime stime cutime cstime priority nice num_threads
        // itrealvalue starttime vsize rss ... — utime=index11, stime=index12,
        // rss(pages)=index21.
        // 22 fields from state through rss inclusive (state,ppid,pgrp,session,tty_nr,
        // tpgid,flags,minflt,cminflt,majflt,cmajflt,utime,stime,cutime,cstime,priority,
        // nice,num_threads,itrealvalue,starttime,vsize,rss) — utime at index 11, stime
        // at 12, rss at 21, matching WebViewPerfMathTest's own field-index comments.
        String proc1 = "111 (system_server) S 1 1 1 0 -1 0 0 0 0 0 300 50 0 0 20 0 4 0 0 0 2000";
        String proc2 = "222 (sandboxed_proces) S 1 1 1 0 -1 0 0 0 0 0 100 20 0 0 20 0 4 0 0 0 5000";
        String procPart = proc1 + "\n" + proc2 + "\n";

        Method parseProcDump = math.getDeclaredMethod("parseProcDump", String.class, String.class);
        parseProcDump.setAccessible(true);
        Object snap = parseProcDump.invoke(null, statPart, procPart);
        Class<?> snapClass = Class.forName("me.jxl.kiosk.plugins.deviceperformance.TopProcessMath$ProcSnapshot");
        Field totalField = snapClass.getDeclaredField("totalJiffies"); totalField.setAccessible(true);
        Field jiffiesField = snapClass.getDeclaredField("processJiffies"); jiffiesField.setAccessible(true);
        Field rssField = snapClass.getDeclaredField("rssPages"); rssField.setAccessible(true);
        Field commField = snapClass.getDeclaredField("commNames"); commField.setAccessible(true);

        assertEquals(9665L, totalField.get(snap), "cpu-line total is the sum of all ten fields (1000+50+500+8000+100+10+5)");
        @SuppressWarnings("unchecked")
        Map<Integer, Long> jiffies = (Map<Integer, Long>) jiffiesField.get(snap);
        assertEquals(350L, jiffies.get(111), "pid 111 utime+stime = 300+50");
        assertEquals(120L, jiffies.get(222), "pid 222 utime+stime = 100+20");
        @SuppressWarnings("unchecked")
        Map<Integer, Long> rss = (Map<Integer, Long>) rssField.get(snap);
        assertEquals(2000L, rss.get(111), "pid 111 rss pages");
        assertEquals(5000L, rss.get(222), "pid 222 rss pages");
        @SuppressWarnings("unchecked")
        Map<Integer, String> comm = (Map<Integer, String>) commField.get(snap);
        assertEquals("system_server", comm.get(111), "pid 111 comm");
        assertEquals("sandboxed_proces", comm.get(222), "pid 222 comm");

        Object emptySnap = parseProcDump.invoke(null, (Object) null, (Object) null);
        assertEquals(-1L, totalField.get(emptySnap), "null input yields -1 total, not a crash");

        Object malformedSnap = parseProcDump.invoke(null, statPart, "not a valid stat line\n" + proc1 + "\n");
        @SuppressWarnings("unchecked")
        Map<Integer, Long> malformedJiffies = (Map<Integer, Long>) jiffiesField.get(malformedSnap);
        assertEquals(1, malformedJiffies.size(), "a malformed line is skipped, the well-formed one still parses");

        Method rankByCpuDelta = math.getDeclaredMethod("rankByCpuDelta", Map.class, Map.class, int.class);
        rankByCpuDelta.setAccessible(true);
        Map<Integer, Long> prev = new HashMap<>();
        prev.put(111, 200L);
        prev.put(222, 100L);
        prev.put(333, 999L); // absent from current — never ranks, not treated as a huge negative delta
        Map<Integer, Long> cur = new HashMap<>();
        cur.put(111, 350L); // delta 150
        cur.put(222, 120L); // delta 20
        cur.put(444, 50L);  // no prior baseline — never ranks (first sample for this pid)
        @SuppressWarnings("unchecked")
        List<Object> ranked = (List<Object>) rankByCpuDelta.invoke(null, cur, prev, 5);
        assertEquals(2, ranked.size(), "only pids with both a prior baseline and a positive delta rank");
        Class<?> rankedClass = Class.forName("me.jxl.kiosk.plugins.deviceperformance.TopProcessMath$Ranked");
        Field pidField = rankedClass.getDeclaredField("pid"); pidField.setAccessible(true);
        Field valueField = rankedClass.getDeclaredField("value"); valueField.setAccessible(true);
        assertEquals(111, pidField.get(ranked.get(0)), "the larger delta (150) ranks first");
        assertEquals(150L, valueField.get(ranked.get(0)), "delta value for the top-ranked pid");
        assertEquals(222, pidField.get(ranked.get(1)), "the smaller delta (20) ranks second");

        Method rankByRam = math.getDeclaredMethod("rankByRam", Map.class, int.class);
        rankByRam.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> ramRanked = (List<Object>) rankByRam.invoke(null, rss, 1);
        assertEquals(1, ramRanked.size(), "limit is respected");
        assertEquals(222, pidField.get(ramRanked.get(0)), "the higher RSS (5000 pages) ranks first, limited to 1");

        Method cpuPercentOfTotal = math.getDeclaredMethod("cpuPercentOfTotal", long.class, long.class);
        cpuPercentOfTotal.setAccessible(true);
        assertEquals(15.0, (double) cpuPercentOfTotal.invoke(null, 150L, 1000L), "150/1000 of total capacity = 15.0%");
        assertEquals(0.0, (double) cpuPercentOfTotal.invoke(null, 150L, 0L), "a non-positive total delta yields 0, not a divide-by-zero");

        Method rssMb = math.getDeclaredMethod("rssMb", long.class, long.class);
        rssMb.setAccessible(true);
        assertEquals(19.5, (double) rssMb.invoke(null, 5000L, 4096L), "5000 pages * 4096 bytes = ~19.5 MB");
        assertEquals(0.0, (double) rssMb.invoke(null, -5L, 4096L), "a negative page count clamps to 0, not a negative MB");

        Method trimName = math.getDeclaredMethod("trimName", String.class);
        trimName.setAccessible(true);
        assertEquals("sandboxed_process", trimName.invoke(null, "sandboxed_process:0"), "a trailing numeric sandbox suffix is trimmed");
        assertEquals("system_server", trimName.invoke(null, "system_server"), "a name with no colon passes through unchanged");
        assertEquals("weird:name", trimName.invoke(null, "weird:name"), "a non-numeric suffix after the colon is left alone");
        assertEquals("", trimName.invoke(null, (Object) null), "null becomes an empty string, not a crash");

        System.out.println("PASS: /proc/stat + /proc/pid/stat dump parsing, CPU/RAM ranking, percent-of-total math, RSS-to-MB conversion, sandbox-suffix trimming.");
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
