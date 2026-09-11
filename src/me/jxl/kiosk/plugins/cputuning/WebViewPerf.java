// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the CrRendererMain jiffy-delta sampling state and runs the root
 * shell probe — the I/O half of WebView responsiveness monitoring; see
 * {@link WebViewPerfMath} for the pure parsing/math this drives.
 *
 * One shell command per tick, ported verbatim from ha-paneld's
 * PerfReader.sampleRender: list every process whose name contains
 * "sandboxe" (Chromium's sandboxed renderer processes — covers a
 * Companion app or a browser dashboard, not just Kiosk Satellite's own
 * WebView), scan each one's threads for `CrRendererMain`, and print its
 * `/proc/.../stat` line. Root-gated: reading another app's `/proc/<pid>`
 * entries needs root on modern Android (`hidepid`).
 *
 * Also retains a rolling ~4-minute history of busy readings (same window
 * ha-paneld's own dashboard-responsiveness card uses) for a p95/peak
 * alongside the latest sample, and counts renderer process replacements
 * over a trailing 24h as a reload count — both directly inspired by
 * ha-paneld's own detailed card ("Main-thread blocking 0.0 ms/s · p95 0
 * ms/s · longest frame 222 ms" / "Renderer reloads (24h) 0 · stable").
 * Not ported: tap response, time-to-interactive and the "likely cause"
 * classifier — those need instrumentation inside the WebView's own JS
 * execution (page-load lifecycle hooks, touch-event timing), which a
 * plugin has no access to under SDK 1.
 */
final class WebViewPerf {
    private static final String PROBE_CMD =
        "ps -A -o PID,NAME 2>/dev/null | grep -i sandboxe | while read pid name; do "
            + "for t in /proc/$pid/task/*; do IFS= read -r c < $t/comm 2>/dev/null || continue; "
            + "if [ \"$c\" = CrRendererMain ]; then IFS= read -r s < $t/stat 2>/dev/null && echo \"$pid $s\"; fi; "
            + "done; done; true";

    /** ~4 minutes at this plugin's 10s diagnostics cadence — the same
     *  window ha-paneld's own card reports over. */
    private static final int MAX_HISTORY_SAMPLES = 24;
    private static final long RELOAD_WINDOW_MS = 24L * 3_600_000L;
    private static final int MAX_RELOAD_SAMPLES = 200;

    static final class Result {
        /** null = no Chromium renderer process found at all (first sample,
         *  or nothing running) — distinct from a real 0% reading. */
        final Double busyPct;
        final String verdict; // null when busyPct is null
        /** ms of main-thread time per second of wall time — the same
         *  reading as busyPct, just in ha-paneld's own "ms/s" units
         *  (ms/s = %busy * 10). Null exactly when busyPct is null. */
        final Double busyMsPerS;
        final Double p95MsPerS; // over the retained history; null with no history yet
        final Double peakMsPerS; // the single highest sample retained
        final int reloadsLast24h;

        private Result(Double busyPct, String verdict, Double busyMsPerS,
                        Double p95MsPerS, Double peakMsPerS, int reloadsLast24h) {
            this.busyPct = busyPct;
            this.verdict = verdict;
            this.busyMsPerS = busyMsPerS;
            this.p95MsPerS = p95MsPerS;
            this.peakMsPerS = peakMsPerS;
            this.reloadsLast24h = reloadsLast24h;
        }

        static Result noRenderer(Double p95MsPerS, Double peakMsPerS, int reloadsLast24h) {
            return new Result(null, null, null, p95MsPerS, peakMsPerS, reloadsLast24h);
        }

        static Result of(double clampedPct, Double p95MsPerS, Double peakMsPerS, int reloadsLast24h) {
            return new Result(clampedPct, WebViewPerfMath.verdict(clampedPct), clampedPct * 10.0,
                p95MsPerS, peakMsPerS, reloadsLast24h);
        }
    }

    /** A retained busy-history sample and the timestamp it was taken at —
     *  for the compact chart this plugin publishes alongside p95/peak, see
     *  {@link #historySnapshot()}. Timestamps pair 1:1 with msPerSHistory,
     *  pushed and evicted together. */
    static final class HistorySnapshot {
        final List<Long> timestampsMs;
        final List<Double> valuesMsPerS;
        HistorySnapshot(List<Long> timestampsMs, List<Double> valuesMsPerS) {
            this.timestampsMs = timestampsMs;
            this.valuesMsPerS = valuesMsPerS;
        }
    }

    private Map<Integer, Long> prevJiffies = new HashMap<>();
    private Long prevSampleAtMs;
    private Set<Integer> prevPids = new HashSet<>();
    private final Deque<Double> msPerSHistory = new ArrayDeque<>();
    private final Deque<Long> historyAtMs = new ArrayDeque<>();
    private final Deque<Long> reloadAtMs = new ArrayDeque<>();

    /** Runs the probe and advances the delta baseline. Call on a worker
     *  thread — this blocks on a root shell round trip. */
    Result tick(long timeoutMs) {
        String out = RootShell.runOutput(PROBE_CMD, timeoutMs);
        Map<Integer, Long> cur = WebViewPerfMath.parseRenderRows(out);
        long now = System.currentTimeMillis();
        double dtSeconds = prevSampleAtMs == null ? -1 : (now - prevSampleAtMs) / 1000.0;
        double pct = WebViewPerfMath.busiestMainPct(prevJiffies, cur, dtSeconds);

        // A renderer replacement: the pid set this tick shares nothing with
        // last tick's, and both ticks actually had a renderer running —
        // never counted from "none -> some" or "some -> none" alone, only
        // a genuine swap.
        Set<Integer> curPids = cur.keySet();
        if (!prevPids.isEmpty() && !curPids.isEmpty() && Collections.disjoint(prevPids, curPids)) {
            reloadAtMs.addLast(now);
            while (reloadAtMs.size() > MAX_RELOAD_SAMPLES) reloadAtMs.removeFirst();
        }
        prevPids = new HashSet<>(curPids);
        prevJiffies = cur;
        prevSampleAtMs = now;

        int reloads = reloadsInWindow(now);
        if (pct < 0) {
            return Result.noRenderer(percentileMsPerS(95.0), peakMsPerS(), reloads);
        }
        double clamped = WebViewPerfMath.clampPct(pct);
        msPerSHistory.addLast(clamped * 10.0);
        historyAtMs.addLast(now);
        while (msPerSHistory.size() > MAX_HISTORY_SAMPLES) msPerSHistory.removeFirst();
        while (historyAtMs.size() > MAX_HISTORY_SAMPLES) historyAtMs.removeFirst();
        return Result.of(clamped, percentileMsPerS(95.0), peakMsPerS(), reloads);
    }

    /** Defensive-copy snapshot of the retained busy-ms/s history, paired
     *  with the wall-clock time each sample was taken — for a compact
     *  chart. Empty when no real (busy-percent-having) samples have been
     *  retained yet, e.g. immediately after {@link #reset()} or while no
     *  renderer has ever been found. */
    HistorySnapshot historySnapshot() {
        return new HistorySnapshot(new ArrayList<>(historyAtMs), new ArrayList<>(msPerSHistory));
    }

    private Double percentileMsPerS(double p) {
        if (msPerSHistory.isEmpty()) return null;
        double result = WebViewPerfMath.percentile(new ArrayList<>(msPerSHistory), p);
        return result < 0 ? null : result;
    }

    private Double peakMsPerS() {
        Double peak = null;
        for (double v : msPerSHistory) if (peak == null || v > peak) peak = v;
        return peak;
    }

    private int reloadsInWindow(long now) {
        long cutoff = now - RELOAD_WINDOW_MS;
        int count = 0;
        for (long t : reloadAtMs) if (t > cutoff) count++;
        return count;
    }

    /** Resets every baseline — call when simulation mode toggles, so a
     *  stale real-hardware baseline (or reload/percentile history) can't
     *  mix with simulated samples, or vice versa. */
    void reset() {
        prevJiffies = new HashMap<>();
        prevSampleAtMs = null;
        prevPids = new HashSet<>();
        msPerSHistory.clear();
        historyAtMs.clear();
        reloadAtMs.clear();
    }
}
