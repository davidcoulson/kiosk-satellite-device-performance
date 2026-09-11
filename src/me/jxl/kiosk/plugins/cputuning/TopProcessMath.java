// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, device-free parsing and ranking for top-processes-by-CPU/RAM — no
 * process launches, no Android APIs. Kept separate from {@link TopProcesses}
 * so it's unit-testable; see test/TopProcessMathTest.java.
 *
 * Ported from ha-paneld's PerfReader.sampleTop/publishTop/rankCpuProcesses/
 * rankRamProcesses, simplified: names come from `/proc/<pid>/stat`'s own
 * `comm` field (kernel-truncated to 16 chars) rather than a second root
 * round-trip to `/proc/<pid>/cmdline` for the full command line — a
 * genuine precision trade (a long package name reads as
 * "sandboxed_proces" rather than the full "com.google.android.webview:
 * sandboxed_process0"), accepted for one probe instead of two.
 */
final class TopProcessMath {
    private TopProcessMath() {}

    static final class ProcSnapshot {
        /** cpu-line aggregate jiffies (sum of all fields on the `cpu ` line
         *  in /proc/stat — user+nice+system+idle+iowait+irq+softirq+...),
         *  or -1 if the stat dump had no `cpu ` line at all. */
        final long totalJiffies;
        final Map<Integer, Long> processJiffies; // pid -> utime+stime
        final Map<Integer, Long> rssPages;        // pid -> resident pages (only where present)
        final Map<Integer, String> commNames;     // pid -> kernel comm (16-char truncated)

        ProcSnapshot(long totalJiffies, Map<Integer, Long> processJiffies,
                     Map<Integer, Long> rssPages, Map<Integer, String> commNames) {
            this.totalJiffies = totalJiffies;
            this.processJiffies = processJiffies;
            this.rssPages = rssPages;
            this.commNames = commNames;
        }
    }

    /**
     * Parses the probe's two sections separately: [statPart] is
     * `cat /proc/stat`'s output (for the aggregate `cpu ` line's total
     * jiffies), [procPart] is every process's own `/proc/&lt;pid&gt;/stat`
     * output concatenated together, one line per process. Malformed
     * individual process lines are skipped, never fatal to the whole
     * sample — a process that vanished mid-scan produces a truncated or
     * missing line, not a crash.
     */
    static ProcSnapshot parseProcDump(String statPart, String procPart) {
        if (statPart == null) statPart = "";
        if (procPart == null) procPart = "";

        long total = -1;
        for (String line : statPart.split("\n")) {
            if (line.startsWith("cpu ")) {
                total = 0;
                for (String field : line.trim().split("\\s+")) {
                    if (field.equals("cpu")) continue;
                    try {
                        total += Long.parseLong(field);
                    } catch (NumberFormatException ignored) {}
                }
                break;
            }
        }

        Map<Integer, Long> processJiffies = new HashMap<>();
        Map<Integer, Long> rssPages = new HashMap<>();
        Map<Integer, String> commNames = new HashMap<>();
        for (String line : procPart.split("\n")) {
            if (line.isEmpty()) continue;
            int lp = line.indexOf('(');
            int rp = line.lastIndexOf(')');
            if (lp <= 0 || rp < lp) continue;
            Integer pid = toIntOrNull(line.substring(0, lp).trim());
            if (pid == null) continue;
            String[] rest = WebViewPerfMath.statFieldsAfterComm(line);
            if (rest == null || rest.length < 22) continue;
            Long utime = toLongOrNull(rest[11]);
            Long stime = toLongOrNull(rest[12]);
            if (utime == null || stime == null) continue;
            processJiffies.put(pid, utime + stime);
            Long rss = toLongOrNull(rest[21]);
            if (rss != null && rss >= 0) rssPages.put(pid, rss);
            commNames.put(pid, line.substring(lp + 1, rp));
        }
        return new ProcSnapshot(total, processJiffies, rssPages, commNames);
    }

    static final class Ranked {
        final int pid;
        final long value; // CPU: jiffy delta; RAM: resident pages
        Ranked(int pid, long value) {
            this.pid = pid;
            this.value = value;
        }
    }

    /** Top [limit] processes by positive jiffy delta between [previous] and
     *  [current] — a process absent from [previous] (just started) or with
     *  a non-positive delta never ranks; ties break by lower pid, matching
     *  ha-paneld's own ordering. */
    static List<Ranked> rankByCpuDelta(Map<Integer, Long> current, Map<Integer, Long> previous, int limit) {
        List<Ranked> ranked = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : current.entrySet()) {
            Long before = previous.get(e.getKey());
            if (before == null) continue;
            long delta = e.getValue() - before;
            if (delta > 0) ranked.add(new Ranked(e.getKey(), delta));
        }
        ranked.sort(Comparator.<Ranked>comparingLong(r -> -r.value).thenComparingInt(r -> r.pid));
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    /** Top [limit] processes by resident pages — descending, ties by lower pid. */
    static List<Ranked> rankByRam(Map<Integer, Long> rssPages, int limit) {
        List<Ranked> ranked = new ArrayList<>();
        for (Map.Entry<Integer, Long> e : rssPages.entrySet()) {
            if (e.getValue() > 0) ranked.add(new Ranked(e.getKey(), e.getValue()));
        }
        ranked.sort(Comparator.<Ranked>comparingLong(r -> -r.value).thenComparingInt(r -> r.pid));
        return ranked.subList(0, Math.min(limit, ranked.size()));
    }

    /** A ranked process's share of TOTAL cpu capacity (sums to <=100
     *  across every process, consistent with an overall-CPU% figure) —
     *  one decimal place, matching ha-paneld's own rounding. */
    static double cpuPercentOfTotal(long jiffyDelta, long totalJiffyDelta) {
        if (totalJiffyDelta <= 0) return 0.0;
        return Math.round(jiffyDelta * 1000.0 / totalJiffyDelta) / 10.0;
    }

    /** Familiar MB units with one decimal, from the kernel's exact
     *  resident-page count and this device's actual page size (ARM64
     *  Android is usually 4096 bytes, but never assumed — some newer
     *  chips use 16K pages). */
    static double rssMb(long pages, long pageSizeBytes) {
        long p = Math.max(0, pages);
        long sz = Math.max(1, pageSizeBytes);
        return Math.round(p * sz * 10.0 / (1024.0 * 1024.0)) / 10.0;
    }

    /** Kernel `comm` is truncated to 16 bytes and can be a bare process
     *  name; trims a trailing `:number` sandbox-instance suffix some
     *  Chromium renderer comms carry, for a tidier display name — the
     *  digits are noise, not identifying. Returns the input unchanged
     *  when there's nothing to trim. */
    static String trimName(String comm) {
        if (comm == null) return "";
        int colon = comm.lastIndexOf(':');
        if (colon <= 0 || colon == comm.length() - 1) return comm;
        String suffix = comm.substring(colon + 1);
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return comm;
        }
        return comm.substring(0, colon);
    }

    private static Integer toIntOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLongOrNull(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
