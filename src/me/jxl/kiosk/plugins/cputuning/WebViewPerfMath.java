// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, device-free parsing and jiffy-delta math for WebView renderer
 * responsiveness — no process launches, no Android APIs. Kept separate
 * from {@link WebViewPerf} so it's unit-testable; see
 * test/WebViewPerfMathTest.java.
 *
 * Ported from ha-paneld's PerfReader.kt (`sampleRender`/`advanceRenderDelta`)
 * and MetricParse.kt (`statFieldsAfterComm`): the dashboard responsiveness
 * signal is the Chromium renderer's `CrRendererMain` thread — the thread
 * the HA frontend processes its WebSocket state firehose on — as
 * %-of-one-core busy time, which saturates near 100% when event handling
 * falls behind *even with zero visible rendering*, the common no-video
 * overload that frame-based jank metrics (`dumpsys gfxinfo`) miss
 * entirely.
 */
final class WebViewPerfMath {
    private WebViewPerfMath() {}

    /**
     * The whitespace-split fields of a `/proc/<pid>/stat` (or
     * `/proc/<pid>/task/<tid>/stat`) line that follow the `comm` field —
     * i.e. starting at the process state (stat field 3). `comm` is
     * wrapped in parentheses and may itself contain spaces and `)` (the
     * kernel truncates it to 16 chars but permits arbitrary bytes), so
     * the split point is the LAST `)`, never the first. Null when there's
     * no `)` or nothing follows the closing `") "`. Index map into the
     * result: state=0, … utime=11, stime=12, cutime=13, cstime=14 (stat
     * fields 3, 14, 15, 16, 17).
     */
    static String[] statFieldsAfterComm(String statLine) {
        if (statLine == null) return null;
        int rp = statLine.lastIndexOf(')');
        if (rp < 0 || rp + 2 > statLine.length()) return null;
        return statLine.substring(rp + 2).split(" ");
    }

    /** One CrRendererMain thread's total (utime+stime) jiffies, keyed by
     *  its owning process pid — parsed from one `"<pid> <stat line>"` row
     *  the shell probe emits per matching thread. Null when the row is
     *  malformed (never thrown — a probe's output can't crash the plugin). */
    static Map.Entry<Integer, Long> parseRenderRow(String row) {
        if (row == null) return null;
        int sp = row.indexOf(' ');
        if (sp <= 0) return null;
        Integer pid = toIntOrNull(row.substring(0, sp).trim());
        if (pid == null) return null;
        String[] rest = statFieldsAfterComm(row.substring(sp + 1));
        if (rest == null || rest.length < 13) return null;
        Long utime = toLongOrNull(rest[11]);
        Long stime = toLongOrNull(rest[12]);
        if (utime == null || stime == null) return null;
        return new java.util.AbstractMap.SimpleEntry<>(pid, utime + stime);
    }

    /** Parses every row the shell probe printed (one line per matching
     *  CrRendererMain thread) into pid -> total jiffies, skipping any
     *  malformed line rather than failing the whole sample. */
    static Map<Integer, Long> parseRenderRows(String output) {
        Map<Integer, Long> result = new HashMap<>();
        if (output == null) return result;
        for (String line : output.split("\n")) {
            Map.Entry<Integer, Long> parsed = parseRenderRow(line);
            if (parsed != null) result.put(parsed.getKey(), parsed.getValue());
        }
        return result;
    }

    /**
     * The busiest CrRendererMain %-of-one-core across every renderer
     * thread between two jiffy snapshots [prev]/[cur] taken [dtSeconds]
     * apart, or -1.0 when there's no prior baseline for ANY current
     * thread (first sample) or no renderer running at all (both maps
     * empty) — the caller reports "no renderer" for -1, never a bogus 0%.
     * Assumes HZ=100 (Android's kernel default), matching the source.
     */
    static double busiestMainPct(Map<Integer, Long> prev, Map<Integer, Long> cur, double dtSeconds) {
        if (cur.isEmpty() || dtSeconds <= 0) return -1.0;
        double best = -1.0;
        for (Map.Entry<Integer, Long> e : cur.entrySet()) {
            Long before = prev.get(e.getKey());
            if (before == null) continue;
            double pct = (e.getValue() - before) / dtSeconds; // jiffies/s == %-of-one-core at HZ=100
            if (pct > best) best = pct;
        }
        return best;
    }

    static final double SMOOTH_BELOW = 50.0;
    static final double OCCASIONAL_BELOW = 85.0;

    /** "smooth", "occasional", or "janky" for a busy percentage already
     *  clamped to 0..100 — thresholds ported verbatim from PerfReader.kt. */
    static String verdict(double pct1) {
        if (pct1 < SMOOTH_BELOW) return "smooth";
        if (pct1 < OCCASIONAL_BELOW) return "occasional";
        return "janky";
    }

    static double clampPct(double pct) {
        if (pct < 0) return 0;
        if (pct > 100) return 100;
        return pct;
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

    /**
     * The [p]th percentile (0..100) of [samples], nearest-rank method —
     * the smallest value at or above which at least [p]% of samples fall.
     * [samples] need not be sorted; this copies and sorts rather than
     * mutating the caller's list. Empty input returns -1 (no data, not a
     * bogus 0). [p] is clamped to 0..100 defensively. Same nearest-rank
     * implementation as the Network Diagnostics plugin's own
     * NetworkMath.percentile — duplicated rather than shared, since
     * plugins are independent artifacts with no cross-plugin dependency
     * mechanism in this SDK.
     */
    static double percentile(List<Double> samples, double p) {
        if (samples.isEmpty()) return -1.0;
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double clamped = Math.max(0.0, Math.min(100.0, p));
        int rank = (int) Math.ceil(clamped / 100.0 * sorted.size());
        int index = Math.max(0, Math.min(sorted.size() - 1, rank - 1));
        return sorted.get(index);
    }
}
