# Device Performance for Kiosk Satellite

Formerly **CPU Performance Mode** — renamed because the plugin covers more than CPU tuning today (WebView responsiveness, top processes by CPU/RAM, and more), and "CPU" undersold it. Same plugin, same history; see [Renamed from CPU Performance Mode](#renamed-from-cpu-performance-mode) below if you had the old one installed.

Tune CPU and GPU governors, Android's system battery saver, and CPU frequency limits on rooted Kiosk Satellite panels — no native code, every control is a root shell command over standard Linux `cpufreq`/`devfreq` sysfs nodes. Also reports live diagnostics: system CPU/RAM/temp and WebView dashboard responsiveness, as both status text and Home Assistant sensor entities, plus a compact WebView-busy history chart.

## Requirements

- Kiosk Satellite with **plugin SDK 1 support**.
- A rooted panel (e.g. Magisk) for the tuning controls. `scaling_governor`, `scaling_min_freq`/`scaling_max_freq` and the GPU `devfreq` governor are all root-only writes on every panel checked so far, unlike this project's [Rockchip LED Control](https://github.com/davidcoulson/kiosk-satellite-rockchip-led-control) plugin, which has a root-free path on some panels. The WebView diagnostic also needs root (reading another app's `/proc/<pid>` entries needs it on modern Android); system CPU/RAM/temp needs none.

## Install and use

1. Wait for a stable GitHub release and its GitHub Actions build to complete.
2. Open **Plugin Manager > Add plugin**, paste this repository URL, review the manifest and README and choose **Trust and install**.
3. Enable **Device Performance** on its entry row and open the subpage.
4. Check the hardware status, then adjust the controls below. Changes save automatically.

The plugin also declares **Check root access** and **Restore defaults** actions. Assign them in Gestures or add a kiosk drawer shortcut / Home Assistant button.

## Controls

| Setting | Behavior |
| --- | --- |
| CPU governor | Auto, Performance or Efficiency. Auto resolves to whichever dynamic (load-following) governor the panel's kernel actually offers — `schedutil`, `interactive`, `ondemand` or `conservative`, varies by SoC. Performance pins every core to the `performance` governor; Efficiency pins every core to `powersave`. |
| GPU governor | The same three tiers, applied to the panel's GPU `devfreq` node if one is found. Shown as "not exposed on this panel" when there isn't one. |
| Android battery saver | Toggles Android's own system Battery Saver (`settings put global low_power`) — a separate, OS-level power mode from the CPU/GPU governor choice. |
| Max CPU frequency | Caps every CPU cluster's top clock speed to this percentage of *that cluster's own* hardware maximum. A big.LITTLE panel's two clusters are capped independently, each relative to its own ceiling — 100% leaves it untouched. Verified taking effect exactly as requested on real hardware. |
| Min CPU frequency | Raises every cluster's floor clock speed to reduce ramp-up lag, same per-cluster percentage math. 0% leaves the floor at the kernel default. **Not verified reliable**: on the panel this was tested against, the kernel accepts the write (no error) but silently coerces the value upward past what was requested — some competing Android/kernel QoS floor already holds a higher minimum than userspace can lower it to. Treat this control as best-effort; it may do less than the percentage suggests, or nothing at all, on a given panel. |
| Simulation mode | Exercises the controls without touching hardware. |

## Diagnostics

Refreshed every 10 seconds, independent of the tuning controls above, and — as of 0.4.0 — also published as real Home Assistant entities (SDK 1's `entities` capability now covers sensors, not just RGB lights, resolving [the upstream feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/2) this section used to link):

| Reading | Mechanism | Root? | HA entity |
| --- | --- | --- | --- |
| System CPU % and temperature | Kiosk Satellite's own `getStats` read command | No | Not republished — Kiosk Satellite already exposes these as its own native `cpu`/`cpu_temp` entities from the same read command, so a second sensor here would just be a confusing duplicate |
| WebView dashboard responsiveness | A root `/proc` probe for the Chromium renderer's `CrRendererMain` thread — smooth / occasional / janky, by %-of-one-core busy time, plus a rolling p95/peak and a 24h renderer-reload count (see below) | Yes | `sensor.webview_busy_percent`, `sensor.webview_p95_ms_per_s`, `sensor.webview_peak_ms_per_s`, `sensor.renderer_reloads_24h` |
| Top processes by CPU and RAM | A root `/proc/stat` + `/proc/<pid>/stat` scan, ranked by jiffy delta (CPU) and resident pages (RAM) — shown as top-3 CPU and top-1 RAM in the compact status line | Yes | Not published as entities — see below |

Every sensor entity publishes on every diagnostics tick, with a null state (Home Assistant's "unknown") rather than a fabricated zero for whatever hasn't been measured yet (e.g. `webview_busy_percent` before the first root probe completes, or on an unrooted panel where it never will). Top processes stay status-text-only: the ranking is a list of dynamically-named rows (whatever process happens to be busiest right now), not a fixed set of keys a sensor entity's schema expects.

### The status text is one reading per line, not a single run-on string

SDK 1's plugin subpage has exactly one slot for free-form runtime text — `host.status()`, a single string with no structured "list of readings" a plugin can address directly (filed as [an upstream feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/5) for a real per-plugin readings list, distinct from the Home Assistant sync above). Short of that, this plugin joins its status lines with `\n` rather than `·`: the on-device subpage renders it with a plain Flutter `Text` widget, which treats `\n` as a real line break — so on the panel itself, this reads as an actual list (tuning summary, then CPU/temp, then WebView, then reloads, then top processes, each on its own line). Remote Admin's web view doesn't benefit the same way (its HTML rendering collapses `\n` back to a space, same single-line look as before) — a gap that request also covers.

### WebView busy history chart

SDK 1 also added bounded time-series charts, resolving [the related chart-rendering gap](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/1) this section used to link. This plugin publishes a compact sparkline of the same rolling ~4-minute `CrRendererMain` busy-ms/s history the p95/peak below are computed from — one line series, updated every diagnostics tick, visible in both the on-device plugin subpage and Remote Admin. Not published until there are at least two retained samples (a one-point chart isn't useful, and the host requires strictly increasing timestamps). Simulation mode fakes the *latest* reading for every other diagnostic but does not fake a chart history — the chart stays empty in simulation, since faking a plausible multi-minute time series felt like more complexity than the demo value justified.

### p95, peak and renderer reloads

Inspired directly by ha-paneld's own "Dashboard responsiveness" detail card (`Main-thread blocking 0.0 ms/s · p95 0 ms/s · longest frame 222 ms` / `Renderer reloads (24h) 0 · stable`). This plugin retains a rolling ~4-minute history of `CrRendererMain` busy readings (same window ha-paneld uses, at this plugin's 10s diagnostics cadence) and reports the p95 and peak alongside the latest sample, both in ha-paneld's own "ms of main-thread time per second of wall time" units. It also counts renderer process replacements — the sandboxed renderer's pid set changing entirely between two ticks — over a trailing 24h as a reload count.

**Not ported**, because they need instrumentation inside the WebView's own JS execution (page-load lifecycle hooks, touch-event timing) that a plugin has no access to under SDK 1: tap response, time-to-interactive, and the "likely cause" classifier.

### Top processes by CPU and RAM

Ported from ha-paneld's `PerfReader.sampleTop`/`rankCpuProcesses`/`rankRamProcesses`: one root shell reads `/proc/stat`'s aggregate `cpu ` line (for total jiffies) and every process's own `/proc/<pid>/stat` line in a single round trip, ranks by positive jiffy delta between two ticks (CPU) and by raw resident-page count (RAM), and converts pages to MB using the device's *actual* page size (`getconf PAGESIZE` — never assumed 4K, since some newer ARM64 chips use 16K pages). Verified against a real 382-process dump on production hardware.

Two simplifications from ha-paneld's own fuller version, both accepted deliberately:

- **Process names** come from `/proc/<pid>/stat`'s own `comm` field (kernel-truncated to 16 bytes) rather than a second root round-trip to `/proc/<pid>/cmdline` for the full command line — a long package name reads as `sandboxed_proces` rather than the full `com.google.android.webview:sandboxed_process0`. One probe instead of two.
- **Display is compact**: top-3 by CPU and top-1 by RAM in the status line, not ha-paneld's full top-5-by-CPU and top-5-by-RAM tables — this plugin's status text has roughly a 1000-character budget shared with every other diagnostic line, not a dedicated dashboard panel.

### Why CrRendererMain, not `dumpsys gfxinfo` frame jank

Ported from ha-paneld's `PerfReader.sampleRender`: the dashboard's real bottleneck on an always-on kiosk is usually the WebView renderer's main thread falling behind on Home Assistant's WebSocket state firehose, not dropped animation frames — and that saturates the `CrRendererMain` thread even with nothing visibly animating, which frame-based jank metrics (`dumpsys gfxinfo`) miss entirely since they only measure rendered frames. The probe lists every process whose name contains `sandboxe` (Chromium's sandboxed renderer processes — this covers a Companion app or a browser dashboard too, not just Kiosk Satellite's own WebView), scans each one's threads for a `CrRendererMain` thread, and computes that thread's %-of-one-core busy time between two samples. Verified end-to-end against a live Kiosk Satellite dashboard: found the real renderer process, matched the real thread, and produced a plausible ~21% busy reading for an idle dashboard.

## Why no overclocking or undervolting

This plugin deliberately does not attempt to raise a CPU past its hardware maximum or adjust voltage. Checked directly against real hardware: `cpuinfo_max_freq` already equals the top entry in `scaling_available_frequencies` — there's no headroom in the vendor's own frequency table to exceed, and the only voltage-related sysfs nodes present are regulator tracing/debug hooks, not writable voltage tables. Real over/underclocking needs a custom kernel exposing those controls, which stock Rockchip vendor kernels don't. It would also be genuinely risky (thermal/electrical stress) on an always-on, wall-mounted panel for no benefit on a dashboard workload.

## Build and test

```sh
export JAVA_HOME=/path/to/jdk
python3 tools/test.py
python3 tools/build.py
```

`tools/test.py` runs device-free unit tests of the governor-resolution and frequency-percent math (including the per-cluster hardware-max scenario from a real big.LITTLE panel), the WebView jiffy-delta parsing (including a `/proc/pid/stat` line whose `comm` field itself contains a stray `)`, the classic bug in a naive port) and chart-payload building, the top-processes `/proc` dump parsing/ranking/RSS-to-MB math, and which of the four sensor entities publish with what state — it proves the logic, not device compatibility. `tools/build.py` produces the ZIP, checksum and manifest in `dist/`.

## Renamed from CPU Performance Mode

This plugin shipped as `cpu-performance-mode` through 0.4.1. Renamed to `device-performance` at 0.5.0 to match its actual scope (WebView responsiveness and top-processes ranking aren't CPU-specific), matching the same full-rename approach [Network ADB](https://github.com/davidcoulson/kiosk-satellite-network-adb) used when it dropped "Wireless" from its own name. The plugin ID changed, which means Home Assistant sees this as a different plugin than before: if you had `cpu-performance-mode` installed, remove it and add this repository fresh — its entities will need to be re-added to any dashboards that referenced the old ones. Settings, tuning behavior, and all diagnostics are otherwise unchanged.

## Publishing and handoff

Apache-2.0. The plugin ID is `device-performance`. See [jxlarrea/kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the SDK 1 documentation this plugin was built against.

Author: David Coulson. Built with AI assistance (Claude Code).
