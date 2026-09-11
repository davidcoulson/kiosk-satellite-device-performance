// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.deviceperformance;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure, device-free logic: governor-name resolution, frequency-percent
 * math and the safety validation that guards every value before it's
 * interpolated into a shell command. Kept separate from
 * {@link DevicePerformancePlugin} so it's unit-testable without an Android device
 * or root — see test/CpuTuningTest.java.
 */
final class TuningMath {
    private TuningMath() {}

    static final String TIER_AUTO = "Auto";
    static final String TIER_PERFORMANCE = "Performance";
    static final String TIER_EFFICIENCY = "Efficiency";

    // Dynamic (load-following) governors, best first — same convention as
    // ha-paneld's CpuController: Auto resolves to whichever of these the
    // panel's kernel actually offers, since it varies by SoC (schedutil on
    // newer Rockchip, interactive on older PX30-class panels).
    private static final String[] DYNAMICS = {"schedutil", "interactive", "ondemand", "conservative"};

    private static final Pattern GOVERNOR_NAME = Pattern.compile("[a-z0-9_]+");

    /** Kernel governor names are lowercase letters/digits/underscore only —
     *  reject anything else before it ever reaches a shell command. */
    static boolean isSafeGovernorName(String name) {
        return name != null && GOVERNOR_NAME.matcher(name).matches();
    }

    /**
     * Resolve a friendly tier (Auto/Performance/Efficiency) to a real
     * kernel governor name from the panel's own available list. Null only
     * when [available] is empty (no cpufreq/devfreq on this node at all).
     */
    static String resolveGovernor(String tier, List<String> available) {
        if (available.isEmpty()) return null;
        String direct;
        switch (tier) {
            case TIER_PERFORMANCE:
                direct = contains(available, "performance") ? "performance" : null;
                break;
            case TIER_EFFICIENCY:
                direct = contains(available, "powersave") ? "powersave" : null;
                break;
            case TIER_AUTO:
                direct = null;
                for (String d : DYNAMICS) {
                    if (contains(available, d)) { direct = d; break; }
                }
                break;
            default:
                direct = null;
        }
        return direct != null ? direct : available.get(0);
    }

    private static boolean contains(List<String> list, String value) {
        for (String s : list) if (s.equals(value)) return true;
        return false;
    }

    /**
     * The frequency (Hz) [percent]% of [hardwareMaxHz] resolves to, never
     * below [hardwareMinHz] and never above [hardwareMaxHz] — a caller-
     * supplied percent outside 0..100 (should never happen: the manifest
     * bounds it, this is defense in depth before shell interpolation) is
     * clamped first.
     */
    static long freqForPercent(int percent, long hardwareMinHz, long hardwareMaxHz) {
        int p = Math.max(0, Math.min(100, percent));
        long hz = Math.round(hardwareMaxHz * (p / 100.0));
        if (hz < hardwareMinHz) hz = hardwareMinHz;
        if (hz > hardwareMaxHz) hz = hardwareMaxHz;
        return hz;
    }

    /** True when [minPercent]'s resolved floor would exceed [maxPercent]'s
     *  resolved ceiling for the same hardware range — an invalid pair the
     *  caller should reorder (max wins: floor cannot exceed ceiling). */
    static boolean floorExceedsCeiling(int minPercent, int maxPercent, long hardwareMinHz, long hardwareMaxHz) {
        return freqForPercent(minPercent, hardwareMinHz, hardwareMaxHz)
            > freqForPercent(maxPercent, hardwareMinHz, hardwareMaxHz);
    }
}
