// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

import java.util.HashMap;
import java.util.Map;

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
 */
final class WebViewPerf {
    private static final String PROBE_CMD =
        "ps -A -o PID,NAME 2>/dev/null | grep -i sandboxe | while read pid name; do "
            + "for t in /proc/$pid/task/*; do IFS= read -r c < $t/comm 2>/dev/null || continue; "
            + "if [ \"$c\" = CrRendererMain ]; then IFS= read -r s < $t/stat 2>/dev/null && echo \"$pid $s\"; fi; "
            + "done; done; true";

    static final class Result {
        /** null = no Chromium renderer process found at all (first sample,
         *  or nothing running) — distinct from a real 0% reading. */
        final Double busyPct;
        final String verdict; // null when busyPct is null

        private Result(Double busyPct, String verdict) {
            this.busyPct = busyPct;
            this.verdict = verdict;
        }

        static final Result NO_RENDERER = new Result(null, null);

        static Result of(double clampedPct) {
            return new Result(clampedPct, WebViewPerfMath.verdict(clampedPct));
        }
    }

    private Map<Integer, Long> prevJiffies = new HashMap<>();
    private Long prevSampleAtMs;

    /** Runs the probe and advances the delta baseline. Call on a worker
     *  thread — this blocks on a root shell round trip. */
    Result tick(long timeoutMs) {
        String out = RootShell.runOutput(PROBE_CMD, timeoutMs);
        Map<Integer, Long> cur = WebViewPerfMath.parseRenderRows(out);
        long now = System.currentTimeMillis();
        double dtSeconds = prevSampleAtMs == null ? -1 : (now - prevSampleAtMs) / 1000.0;
        double pct = WebViewPerfMath.busiestMainPct(prevJiffies, cur, dtSeconds);
        prevJiffies = cur;
        prevSampleAtMs = now;
        if (pct < 0) return Result.NO_RENDERER;
        return Result.of(WebViewPerfMath.clampPct(pct));
    }

    /** Resets the delta baseline — call when simulation mode toggles, so a
     *  stale real-hardware baseline can't produce a bogus first delta
     *  against a simulated sample, or vice versa. */
    void reset() {
        prevJiffies = new HashMap<>();
        prevSampleAtMs = null;
    }
}
