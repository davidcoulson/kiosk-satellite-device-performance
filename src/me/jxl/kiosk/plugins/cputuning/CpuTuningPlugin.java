// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

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
 */
public final class CpuTuningPlugin implements KioskPlugin {
    private final AtomicBoolean alive = new AtomicBoolean();
    private PluginHost host;
    private ExecutorService worker;
    private Map<String, Object> settings = new HashMap<>();

    // Populated by detect(); null fields mean "not available on this panel".
    private volatile boolean rooted;
    private volatile List<String> cpuGovernors = Collections.emptyList();
    private volatile String gpuNodePath;
    private volatile List<String> gpuGovernors = Collections.emptyList();
    private Boolean lastSimulation;

    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        alive.set(true);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cpu-tuning");
            t.setDaemon(true);
            return t;
        });
        configure(settings);
    }

    public void configure(Map<String, Object> values) {
        Map<String, Object> copy = new HashMap<>(values);
        submit(() -> {
            boolean simulation = Boolean.TRUE.equals(copy.get("simulation"));
            boolean recheck = lastSimulation == null || lastSimulation != simulation;
            settings = copy;
            lastSimulation = simulation;
            if (recheck) detect();
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
                    defaults.put("cpuTier", CpuTuningMath.TIER_AUTO);
                    defaults.put("gpuTier", CpuTuningMath.TIER_AUTO);
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

        String cpuTier = String.valueOf(settings.getOrDefault("cpuTier", CpuTuningMath.TIER_AUTO));
        String cpuGov = cpuGovernors.isEmpty() ? null : CpuTuningMath.resolveGovernor(cpuTier, cpuGovernors);
        if (cpuGov != null && CpuTuningMath.isSafeGovernorName(cpuGov)) {
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

        String gpuTier = String.valueOf(settings.getOrDefault("gpuTier", CpuTuningMath.TIER_AUTO));
        String gpuGov = gpuNodePath != null && !gpuGovernors.isEmpty()
            ? CpuTuningMath.resolveGovernor(gpuTier, gpuGovernors) : null;
        if (gpuGov != null && CpuTuningMath.isSafeGovernorName(gpuGov)) {
            script.append("echo ").append(gpuGov).append(" > \"").append(gpuNodePath).append("/governor\" 2>/dev/null\n");
        }

        boolean batterySaver = Boolean.TRUE.equals(settings.get("batterySaver"));
        script.append("settings put global low_power ").append(batterySaver ? 1 : 0).append(" 2>/dev/null\n");

        boolean ok = RootShell.run(script.toString(), RootShell.COMMAND_TIMEOUT_MS);
        status(ok);
    }

    private void status(boolean ok) {
        if (!alive.get()) return;
        if (Boolean.TRUE.equals(settings.get("simulation"))) {
            host.status("Simulation mode. No hardware is being changed.", false);
            return;
        }
        if (!ok) {
            host.status("One or more tuning writes failed — check the panel's kernel exposes these controls.", true);
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("CPU: ").append(settings.getOrDefault("cpuTier", CpuTuningMath.TIER_AUTO));
        if (gpuNodePath != null) {
            msg.append(" · GPU: ").append(settings.getOrDefault("gpuTier", CpuTuningMath.TIER_AUTO));
        } else {
            msg.append(" · GPU: not exposed on this panel");
        }
        msg.append(" · Battery saver: ")
            .append(Boolean.TRUE.equals(settings.get("batterySaver")) ? "on" : "off");
        host.status(msg.toString(), false);
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
            String cpuGov = cpuGovernors.isEmpty() ? null : CpuTuningMath.resolveGovernor(CpuTuningMath.TIER_AUTO, cpuGovernors);
            StringBuilder script = new StringBuilder();
            if (cpuGov != null && CpuTuningMath.isSafeGovernorName(cpuGov)) {
                script.append("for p in /sys/devices/system/cpu/cpufreq/policy*; do ")
                    .append("echo ").append(cpuGov).append(" > \"$p/scaling_governor\" 2>/dev/null; ")
                    .append("maxHw=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null); minHw=$(cat \"$p/cpuinfo_min_freq\" 2>/dev/null); ")
                    .append("[ -n \"$maxHw\" ] && echo $maxHw > \"$p/scaling_max_freq\" 2>/dev/null; ")
                    .append("[ -n \"$minHw\" ] && echo $minHw > \"$p/scaling_min_freq\" 2>/dev/null; ")
                    .append("done\n");
            }
            if (gpuNodePath != null && !gpuGovernors.isEmpty()) {
                String gpuGov = CpuTuningMath.resolveGovernor(CpuTuningMath.TIER_AUTO, gpuGovernors);
                if (gpuGov != null && CpuTuningMath.isSafeGovernorName(gpuGov)) {
                    script.append("echo ").append(gpuGov).append(" > \"").append(gpuNodePath).append("/governor\" 2>/dev/null\n");
                }
            }
            script.append("settings put global low_power 0 2>/dev/null\n");
            RootShell.run(script.toString(), RootShell.COMMAND_TIMEOUT_MS);
        }
    }
}
