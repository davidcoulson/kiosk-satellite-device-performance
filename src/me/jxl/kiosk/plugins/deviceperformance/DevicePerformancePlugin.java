// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.deviceperformance;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * CPU/GPU governor tiers, Android battery saver, and CPU frequency
 * capping — all plain root shell commands over standard Linux
 * cpufreq/devfreq sysfs, no native code and no KS entities/hardware
 * access. Requires root: unlike the panel LED, none of these nodes are
 * ever directly writable by an ordinary app (SELinux denies it
 * categorically on every panel checked), so there is no direct-access
 * path to fall back to — only "rooted" or "unavailable".
 *
 * Every actual change is one shell script covering every setting at once
 * (never a per-field diff): CPU/GPU governor selection and frequency caps
 * are cheap, idempotent, infrequent, user-triggered writes, so reapplying
 * the full desired state on every configure() is simpler and harder to
 * get out of sync than tracking which field changed.
 *
 * Diagnostics (declares host.read and entities) run on their own periodic
 * timer, independent of tuning changes: system CPU/RAM/temp via the
 * host's own `getStats` read command (no root — KS already tracks this),
 * and WebView dashboard responsiveness via a root /proc probe for the
 * Chromium renderer's CrRendererMain thread (see WebViewPerf) — ported
 * from ha-paneld's PerfReader. Every reading also publishes as a real SDK
 * 1 sensor entity (see WebViewEntities), and the WebView busy-history is
 * published as a compact chart (see WebViewPerfMath.webViewChart) — both
 * resolve upstream SDK gaps this plugin's README used to document as
 * limitations. All of it feeds into the same status line the tuning
 * summary already uses.
 */
public final class DevicePerformancePlugin implements KioskPlugin {
    private static final long DIAGNOSTICS_INTERVAL_S = 10L;

    private final AtomicBoolean alive = new AtomicBoolean();
    private PluginHost host;
    private ScheduledExecutorService worker;
    private Map<String, Object> settings = new HashMap<>();
    private final WebViewPerf webViewPerf = new WebViewPerf();
    private final TopProcesses topProcesses = new TopProcesses();

    // Populated by detect(); null fields mean "not available on this panel".
    private volatile boolean rooted;
    private volatile List<String> cpuGovernors = Collections.emptyList();
    private volatile String gpuNodePath;
    private volatile List<String> gpuGovernors = Collections.emptyList();
    private Boolean lastSimulation;

    // Latest diagnostics, refreshed by the periodic tick and folded into
    // whichever status line is composed next (tuning-change or diagnostics
    // tick, whichever runs last wins — host.status() has no history).
    private volatile Map<?, ?> latestStats; // {battery, charging, cpu, temp} from getStats
    private volatile WebViewPerf.Result latestRender; // null until the first diagnostics tick
    private volatile TopProcesses.Result latestTop = TopProcesses.Result.EMPTY;
    private ScheduledFuture<?> diagnosticsTask;

    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        alive.set(true);
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cpu-tuning");
            t.setDaemon(true);
            return t;
        });
        diagnosticsTask = worker.scheduleWithFixedDelay(
            () -> submit(this::runDiagnosticsTick), 0, DIAGNOSTICS_INTERVAL_S, TimeUnit.SECONDS);
        configure(settings);
    }

    public void configure(Map<String, Object> values) {
        Map<String, Object> copy = new HashMap<>(values);
        submit(() -> {
            boolean simulation = Boolean.TRUE.equals(copy.get("simulation"));
            boolean recheck = lastSimulation == null || lastSimulation != simulation;
            settings = copy;
            lastSimulation = simulation;
            if (recheck) {
                detect();
                webViewPerf.reset(); // don't diff a real-hardware baseline against simulated samples or vice versa
                topProcesses.reset();
            }
            apply();
        });
    }

    public void execute(String command, Map<String, Object> args) {
        submit(() -> {
            switch (command) {
                case "detect":
                    detect();
                    apply();
                    break;
                case "restoreDefaults": {
                    Map<String, Object> defaults = new HashMap<>();
                    defaults.put("cpuTier", TuningMath.TIER_AUTO);
                    defaults.put("gpuTier", TuningMath.TIER_AUTO);
                    defaults.put("batterySaver", false);
                    defaults.put("maxFreqPercent", 100);
                    defaults.put("minFreqPercent", 0);
                    defaults.put("simulation", settings.get("simulation"));
                    settings = defaults;
                    apply();
                    host.saveSettings(settings);
                    break;
                }
                default:
                    throw new IllegalArgumentException("Unknown command");
            }
        });
    }

    public void onEvent(String event, Map<String, Object> payload) {
        // No window, no light — nothing to observe.
    }

    /** Probe root + which governor/frequency nodes this panel actually
     *  exposes. Runs on the worker thread: an unauthorized `su` request
     *  can block on an interactive grant dialog, so this must never run
     *  on the caller's thread inside a lifecycle callback. */
    private void detect() {
        if (Boolean.TRUE.equals(settings.get("simulation"))) {
            rooted = true;
            cpuGovernors = Arrays.asList("performance", "powersave", "schedutil");
            gpuNodePath = null;
            gpuGovernors = Collections.emptyList();
            return;
        }
        rooted = RootShell.isRooted();
        if (!rooted) {
            cpuGovernors = Collections.emptyList();
            gpuNodePath = null;
            gpuGovernors = Collections.emptyList();
            return;
        }
        String out = RootShell.runOutput(
            "cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors 2>/dev/null; echo '---'; "
                + "g=$(ls -d /sys/class/devfreq/*gpu* 2>/dev/null | head -1); echo \"$g\"; echo '---'; "
                + "[ -n \"$g\" ] && cat \"$g/available_governors\" 2>/dev/null",
            RootShell.DETECT_TIMEOUT_MS
        );
        if (out == null) {
            cpuGovernors = Collections.emptyList();
            gpuNodePath = null;
            gpuGovernors = Collections.emptyList();
            return;
        }
        String[] parts = out.split("---", -1);
        cpuGovernors = splitWords(parts.length > 0 ? parts[0] : "");
        String node = parts.length > 1 ? parts[1].trim() : "";
        gpuNodePath = (!node.isEmpty() && node.matches("/sys/class/devfreq/[A-Za-z0-9_.-]+")) ? node : null;
        gpuGovernors = gpuNodePath != null && parts.length > 2 ? splitWords(parts[2]) : Collections.emptyList();
    }

    private static List<String> splitWords(String s) {
        List<String> words = new ArrayList<>();
        for (String w : s.trim().split("\\s+")) if (!w.isEmpty()) words.add(w);
        return words;
    }

    /** Reapply every setting's full effect in one shell script. Cheap: this
     *  only runs on an actual settings change, never on a timer. */
    private void apply() {
        if (settings.isEmpty()) return;
        boolean simulation = Boolean.TRUE.equals(settings.get("simulation"));
        if (simulation) {
            status(true);
            return;
        }
        if (!rooted) {
            host.status("Root access is required for CPU/GPU tuning and isn't available on this panel.", true);
            return;
        }

        StringBuilder script = new StringBuilder();

        String cpuTier = String.valueOf(settings.getOrDefault("cpuTier", TuningMath.TIER_AUTO));
        String cpuGov = cpuGovernors.isEmpty() ? null : TuningMath.resolveGovernor(cpuTier, cpuGovernors);
        if (cpuGov != null && TuningMath.isSafeGovernorName(cpuGov)) {
            script.append("for p in /sys/devices/system/cpu/cpufreq/policy*; do echo ")
                .append(cpuGov).append(" > \"$p/scaling_governor\" 2>/dev/null; done\n");
        }

        // maxFreqPercent is verified taking effect exactly as requested on real
        // hardware (the kernel snaps to the nearest available OPP, as expected).
        // minFreqPercent is best-effort: on the panel this was tested against,
        // the write is accepted (exit 0) but silently coerced upward past the
        // requested value by a floor already held elsewhere (an Android/kernel
        // QoS vote, not this plugin) — see the README's Min CPU frequency note.
        int maxPct = ((Number) settings.getOrDefault("maxFreqPercent", 100)).intValue();
        int minPct = ((Number) settings.getOrDefault("minFreqPercent", 0)).intValue();
        maxPct = Math.max(0, Math.min(100, maxPct));
        minPct = Math.max(0, Math.min(100, minPct));
        if (cpuGov != null) {
            script.append("for p in /sys/devices/system/cpu/cpufreq/policy*; do ")
                .append("maxHw=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null); minHw=$(cat \"$p/cpuinfo_min_freq\" 2>/dev/null); ")
                .append("[ -z \"$maxHw\" ] && continue; [ -z \"$minHw\" ] && minHw=0; ")
                .append("newMax=$(( maxHw * ").append(maxPct).append(" / 100 )); ")
                .append("newMin=$(( maxHw * ").append(minPct).append(" / 100 )); ")
                .append("[ \"$newMax\" -lt \"$minHw\" ] && newMax=$minHw; ")
                .append("[ \"$newMin\" -lt \"$minHw\" ] && newMin=$minHw; ")
                .append("[ \"$newMin\" -gt \"$newMax\" ] && newMin=$newMax; ")
                .append("echo $newMax > \"$p/scaling_max_freq\" 2>/dev/null; ")
                .append("echo $newMin > \"$p/scaling_min_freq\" 2>/dev/null; ")
                .append("done\n");
        }

        String gpuTier = String.valueOf(settings.getOrDefault("gpuTier", TuningMath.TIER_AUTO));
        String gpuGov = gpuNodePath != null && !gpuGovernors.isEmpty()
            ? TuningMath.resolveGovernor(gpuTier, gpuGovernors) : null;
        if (gpuGov != null && TuningMath.isSafeGovernorName(gpuGov)) {
            script.append("echo ").append(gpuGov).append(" > \"").append(gpuNodePath).append("/governor\" 2>/dev/null\n");
        }

        boolean batterySaver = Boolean.TRUE.equals(settings.get("batterySaver"));
        script.append("settings put global low_power ").append(batterySaver ? 1 : 0).append(" 2>/dev/null\n");

        boolean ok = RootShell.run(script.toString(), RootShell.COMMAND_TIMEOUT_MS);
        status(ok);
    }

    private void status(boolean ok) {
        if (!alive.get()) return;
        boolean simulation = Boolean.TRUE.equals(settings.get("simulation"));
        if (!simulation && !ok) {
            host.status("One or more tuning writes failed — check the panel's kernel exposes these controls.", true);
            return;
        }
        host.status(composeStatus(simulation), false);
    }

    /** Diagnostics (system stats + WebView responsiveness) run on their
     *  own timer and refresh independently of tuning changes — this is
     *  what that timer calls, always showing the full composed status
     *  since a diagnostics tick never represents a tuning-write outcome. */
    private void refreshDiagnosticsStatus() {
        if (!alive.get()) return;
        host.status(composeStatus(Boolean.TRUE.equals(settings.get("simulation"))), false);
        publishEntities();
    }

    /** Publishes WebViewEntities' four always-present sensors (null state means
     *  "not known yet", not "missing entity" — see WebViewEntities' class
     *  doc), plus the WebView busy-history chart when there's enough
     *  retained history to draw one. Deliberately excludes system CPU %
     *  and temperature — Kiosk Satellite already publishes those as its
     *  own native entities. */
    private void publishEntities() {
        WebViewPerf.Result render = latestRender;
        List<WebViewEntities.Entity> entities = WebViewEntities.compute(
            render == null ? null : render.busyPct,
            render == null ? null : render.p95MsPerS,
            render == null ? null : render.peakMsPerS,
            render == null ? null : render.reloadsLast24h);
        for (WebViewEntities.Entity e : entities) {
            host.publishSensor(e.key, e.name, e.metadata, e.state);
        }
        host.publishTextSensor(WebViewEntities.VERDICT_KEY, WebViewEntities.VERDICT_NAME,
            render == null ? null : render.verdict);

        WebViewPerf.HistorySnapshot history = webViewPerf.historySnapshot();
        Map<String, Object> chart = WebViewPerfMath.webViewChart(history.timestampsMs, history.valuesMsPerS);
        if (chart != null) {
            host.publishSeries("webview_busy", chart);
        } else {
            host.removeSeries("webview_busy");
        }
    }

    /** Deliberately short, and deliberately NOT a copy of the readings:
     *  every WebView number this plugin measures is published as an entity,
     *  and the host now renders those as a real label/value list in its own
     *  Readings section (upstream's "Show live plugin readings in native
     *  settings and Remote Admin", resolving the feature request this
     *  plugin's README links). Repeating them here would just be the same
     *  data twice on one screen.
     *
     *  What stays is what the Readings list can't show: the current tuning
     *  configuration (settings, not measurements), and the top-processes
     *  ranking (dynamically-named rows, which don't fit a fixed entity
     *  schema — see appendTopProcesses). Still `\n`-joined, one item per
     *  line, since the on-device Text widget renders real line breaks. */
    private String composeStatus(boolean simulation) {
        List<String> lines = new ArrayList<>();
        StringBuilder tuning = new StringBuilder();
        tuning.append("CPU: ").append(settings.getOrDefault("cpuTier", TuningMath.TIER_AUTO));
        if (gpuNodePath != null) {
            tuning.append(" · GPU: ").append(settings.getOrDefault("gpuTier", TuningMath.TIER_AUTO));
        } else {
            tuning.append(" · GPU: not exposed on this panel");
        }
        tuning.append(" · Battery saver: ")
            .append(Boolean.TRUE.equals(settings.get("batterySaver")) ? "on" : "off");
        if (simulation) tuning.append(" · Simulation mode");
        lines.add(tuning.toString());
        appendDiagnostics(lines);
        return String.join("\n", lines);
    }

    private void appendDiagnostics(List<String> lines) {
        // Only the states the Readings list can't express: "measuring…"
        // and "no renderer detected" are the absence of a reading, which
        // a null-stated entity shows as a bare "No data" without saying
        // which of the two it is.
        WebViewPerf.Result render = latestRender;
        if (render == null) {
            lines.add("WebView: measuring…");
        } else if (render.busyPct == null) {
            lines.add("WebView: no renderer detected");
        }
        appendTopProcesses(lines);
    }

    /** Top-3 CPU consumers and the single top RAM consumer — a compact
     *  stand-in for ha-paneld's own full top-5-by-CPU/top-5-by-RAM tables,
     *  which don't fit a 1000-character status. Names come from the
     *  kernel's own 16-char-truncated `comm` field, not the full
     *  package/command line — see TopProcessMath's class doc for why. */
    private void appendTopProcesses(List<String> lines) {
        TopProcesses.Result top = latestTop;
        if (!top.byCpu.isEmpty()) {
            StringBuilder line = new StringBuilder("Top CPU: ");
            for (int i = 0; i < Math.min(3, top.byCpu.size()); i++) {
                if (i > 0) line.append(", ");
                TopProcesses.Row r = top.byCpu.get(i);
                line.append(r.name).append(" ").append(formatNumber(r.value)).append("%");
            }
            lines.add(line.toString());
        }
        if (!top.byRam.isEmpty()) {
            TopProcesses.Row r = top.byRam.get(0);
            lines.add("Top RAM: " + r.name + " " + formatNumber(r.value) + "MB");
        }
    }

    private static String formatNumber(Object n) {
        if (!(n instanceof Number)) return String.valueOf(n);
        double d = ((Number) n).doubleValue();
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    /** System CPU/RAM/temp (host.read, no root — KS already tracks this),
     *  WebView dashboard responsiveness, and top processes by CPU/RAM
     *  (both root, this plugin's own /proc probes) — independent of each
     *  other and of tuning changes, on their own fixed cadence. */
    private void runDiagnosticsTick() {
        boolean simulation = Boolean.TRUE.equals(settings.get("simulation"));
        if (simulation) {
            Map<String, Object> fake = new HashMap<>();
            fake.put("cpu", 22);
            fake.put("temp", 41.5);
            latestStats = fake;
            latestRender = WebViewPerf.Result.of(37.5, 41.0, 52.0, 0);
            latestTop = new TopProcesses.Result(
                Arrays.asList(
                    new TopProcesses.Row("sandboxed_proces", 12.3),
                    new TopProcesses.Row("system_server", 4.1),
                    new TopProcesses.Row("surfaceflinger", 2.1)),
                Collections.singletonList(new TopProcesses.Row("com.android.chrome", 340.5)));
            refreshDiagnosticsStatus();
            return;
        }
        host.executeCommand("getStats", Collections.emptyMap(), (ok, data, error) -> submit(() -> {
            if (ok && data instanceof Map) latestStats = (Map<?, ?>) data;
            refreshDiagnosticsStatus();
        }));
        if (rooted) {
            latestRender = webViewPerf.tick(RootShell.COMMAND_TIMEOUT_MS);
            latestTop = topProcesses.tick(RootShell.COMMAND_TIMEOUT_MS);
            refreshDiagnosticsStatus();
        }
    }

    private interface Task { void run() throws Exception; }

    private void submit(Task task) {
        if (!alive.get()) return;
        worker.execute(() -> {
            if (!alive.get()) return;
            try {
                task.run();
            } catch (Exception e) {
                host.status(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), true);
            }
        });
    }

    public void stop() throws Exception {
        alive.set(false);
        if (diagnosticsTask != null) diagnosticsTask.cancel(false);
        worker.shutdownNow();
        worker.awaitTermination(1000, TimeUnit.MILLISECONDS);
        // Best-effort restore to the safe defaults (Auto/Auto, no caps,
        // battery saver off) so disabling or uninstalling this plugin
        // doesn't strand the panel on a Performance/Efficiency governor
        // or a frequency cap forever. Bounded short: stop() must return
        // promptly, and by this point root is already-authorized from
        // earlier use in this session, so a fresh interactive grant
        // dialog is not expected.
        if (rooted && !Boolean.TRUE.equals(settings.get("simulation"))) {
            String cpuGov = cpuGovernors.isEmpty() ? null : TuningMath.resolveGovernor(TuningMath.TIER_AUTO, cpuGovernors);
            StringBuilder script = new StringBuilder();
            if (cpuGov != null && TuningMath.isSafeGovernorName(cpuGov)) {
                script.append("for p in /sys/devices/system/cpu/cpufreq/policy*; do ")
                    .append("echo ").append(cpuGov).append(" > \"$p/scaling_governor\" 2>/dev/null; ")
                    .append("maxHw=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null); minHw=$(cat \"$p/cpuinfo_min_freq\" 2>/dev/null); ")
                    .append("[ -n \"$maxHw\" ] && echo $maxHw > \"$p/scaling_max_freq\" 2>/dev/null; ")
                    .append("[ -n \"$minHw\" ] && echo $minHw > \"$p/scaling_min_freq\" 2>/dev/null; ")
                    .append("done\n");
            }
            if (gpuNodePath != null && !gpuGovernors.isEmpty()) {
                String gpuGov = TuningMath.resolveGovernor(TuningMath.TIER_AUTO, gpuGovernors);
                if (gpuGov != null && TuningMath.isSafeGovernorName(gpuGov)) {
                    script.append("echo ").append(gpuGov).append(" > \"").append(gpuNodePath).append("/governor\" 2>/dev/null\n");
                }
            }
            script.append("settings put global low_power 0 2>/dev/null\n");
            RootShell.run(script.toString(), RootShell.COMMAND_TIMEOUT_MS);
        }
    }
}
