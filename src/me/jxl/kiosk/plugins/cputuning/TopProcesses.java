// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the /proc-stat jiffy-delta sampling state and runs the root shell
 * probe for top-processes-by-CPU/RAM — the I/O half; see
 * {@link TopProcessMath} for the pure parsing/ranking this drives.
 *
 * One shell command per tick: the aggregate `/proc/stat` line (for total
 * jiffies, the denominator of "% of total capacity"), then every
 * process's own `/proc/<pid>/stat` line, then the device's real page
 * size — root-gated, since `/proc/<pid>` for another app's pid is hidden
 * from an ordinary app by `hidepid` on modern Android.
 */
final class TopProcesses {
    private static final int LIMIT = 5;
    private static final String PROBE_CMD =
        "cat /proc/stat; echo @@; cat /proc/[0-9]*/stat 2>/dev/null; echo @@; "
            + "getconf PAGESIZE 2>/dev/null; true";

    static final class Row {
        final String name;
        final double value; // CPU: percent of total capacity; RAM: MB
        Row(String name, double value) {
            this.name = name;
            this.value = value;
        }
    }

    static final class Result {
        final List<Row> byCpu;
        final List<Row> byRam;
        Result(List<Row> byCpu, List<Row> byRam) {
            this.byCpu = byCpu;
            this.byRam = byRam;
        }
        static final Result EMPTY = new Result(new ArrayList<>(), new ArrayList<>());
    }

    private Map<Integer, Long> prevJiffies = new HashMap<>();
    private long prevTotalJiffies = -1;

    /** Runs the probe and advances the delta baseline. Call on a worker
     *  thread — this blocks on a root shell round trip scanning every
     *  process on the panel. */
    Result tick(long timeoutMs) {
        String out = RootShell.runOutput(PROBE_CMD, timeoutMs);
        String[] parts = out == null ? new String[0] : out.split("\n@@\n");
        TopProcessMath.ProcSnapshot snap = TopProcessMath.parseProcDump(
            parts.length > 0 ? parts[0] : null,
            parts.length > 1 ? parts[1] : null);
        long pageSize = parts.length > 2 ? parsePageSize(parts[2]) : 4096L;

        List<Row> cpuRows = new ArrayList<>();
        long totalDelta = (prevTotalJiffies >= 0 && snap.totalJiffies >= 0) ? snap.totalJiffies - prevTotalJiffies : -1;
        if (totalDelta > 0) {
            for (TopProcessMath.Ranked r : TopProcessMath.rankByCpuDelta(snap.processJiffies, prevJiffies, LIMIT)) {
                String name = TopProcessMath.trimName(snap.commNames.getOrDefault(r.pid, "pid " + r.pid));
                cpuRows.add(new Row(name, TopProcessMath.cpuPercentOfTotal(r.value, totalDelta)));
            }
        }

        List<Row> ramRows = new ArrayList<>();
        for (TopProcessMath.Ranked r : TopProcessMath.rankByRam(snap.rssPages, LIMIT)) {
            String name = TopProcessMath.trimName(snap.commNames.getOrDefault(r.pid, "pid " + r.pid));
            ramRows.add(new Row(name, TopProcessMath.rssMb(r.value, pageSize)));
        }

        prevJiffies = snap.processJiffies;
        prevTotalJiffies = snap.totalJiffies;
        return new Result(cpuRows, ramRows);
    }

    private static long parsePageSize(String raw) {
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : 4096L;
        } catch (Exception e) {
            return 4096L;
        }
    }

    /** Resets the delta baseline — call when simulation mode toggles, so a
     *  stale real-hardware baseline can't produce a bogus first delta
     *  against a simulated sample, or vice versa. */
    void reset() {
        prevJiffies = new HashMap<>();
        prevTotalJiffies = -1;
    }
}
