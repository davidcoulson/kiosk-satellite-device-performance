# Changelog

## 0.7.0

- **One root shell per plugin instead of one per command.** Every root call used to spawn a fresh `su`, and Magisk shows its "granted Superuser rights" toast per request. The plugin now holds a single `su` session and writes commands to its stdin, so root is granted once per plugin start.
- This plugin was the main source: a 10-second tick spawning two `su` processes is twelve grants a minute, enough that Android began discarding the toasts for exceeding its quota — which is how the problem surfaced at all.
- Process spawning also isn't free on the low-end panels this plugin exists to measure. Measuring a panel shouldn't be a measurable part of that panel's load.
- Commands are framed by a per-session random sentinel (`echo <token>:$?`), so exit codes and output read exactly as before. Each command runs in a subshell, so one containing `exit` ends that subshell rather than silently killing the session and costing root for the rest of the plugin's life.
- A timeout or a dead shell closes the session and the next call opens a clean one. Late output from a timed-out command can't be told apart from the next command's, so resynchronising would be guesswork — it's killed instead. Failures cost one extra grant, never silent corruption.
- The session ends with the plugin: `stop()` closes it, so disabling the plugin doesn't leave a root shell alive.
- Tested against `sh` rather than `su`, which needs no root or device: the load-bearing assertion is that two commands report the same PID, since a regression to per-command spawning would only show up as toast spam on a panel.

## 0.6.0

- Upstream shipped a real Readings list ("Show live plugin readings in native settings and Remote Admin", resolving [the feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/5) filed for exactly this), so every published measurement now renders as a proper label/value row on-device *and* in Remote Admin. Status text is trimmed to match: it no longer repeats the WebView numbers that now have their own rows, keeping only what the list can't express — the tuning configuration, the top-processes ranking, and the `measuring…` vs `no renderer detected` distinction a null-stated entity would flatten to "No data".
- Publish the smooth/occasional/janky verdict as its own `webview_verdict` text sensor, so it appears in the Readings list (and Home Assistant) rather than existing only inside status text.
- Numeric sensors now carry `accuracyDecimals: 1`, so the Readings list shows one decimal instead of rounding sub-percent movement away.

## 0.5.0

- **Renamed from `cpu-performance-mode` to `device-performance`** (display name "CPU Performance Mode" → "Device Performance"), matching Network ADB's earlier full rename precedent. This is a breaking change for existing installs — see "Renamed from CPU Performance Mode" in the README. Repository, Java package (`cputuning` → `deviceperformance`), and entry class (`CpuTuningPlugin` → `DevicePerformancePlugin`) renamed to match. `CpuTuningMath`/`CpuEntities` also renamed to `TuningMath`/`WebViewEntities` — the latter no longer had "Cpu" in its actual content after 0.4.1 removed the CPU/temperature entities.
- Status text now joins with `\n` (one reading per line) instead of `·` (one long run-on line). Renders as a real list on-device (Flutter's `Text` widget treats `\n` as a line break); Remote Admin's web view still collapses it to one line, a gap tracked in [the upstream feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/5) filed alongside this change for a real structured readings list.

## 0.4.1

- Fix: 0.4.0 republished system CPU % and temperature as this plugin's own `sensor.cpu_percent`/`sensor.temperature` entities, duplicating Kiosk Satellite's existing native `cpu`/`cpu_temp` entities (same underlying `getStats` reading). Removed — this plugin now publishes only the four WebView-related sensors, which have no existing equivalent.

## 0.4.0

- Publish every diagnostics reading as a real Home Assistant sensor entity — system CPU %, temperature, WebView busy %, WebView p95/peak ms/s, and 24h renderer reload count — alongside the existing status text. Null state ("unknown" in HA) rather than a fabricated zero for anything not measured yet. Declares the `entities` capability. Top processes stay status-text-only (dynamically-named rows don't fit a fixed sensor schema). **See 0.4.1**: the CPU %/temperature entities were removed one release later as duplicates of Kiosk Satellite's own native entities.
- Publish a compact WebView main-thread-busy history chart (`publishSeries`), reusing the same rolling ~4-minute history the p95/peak are computed from. Resolves the two upstream SDK gaps this plugin's README used to document as limitations, now shipped in jxlarrea/kiosk-satellite's "Add SDK 1 plugin charts and compact sparklines" and "Add SDK 1 plugin sensors, selects and bar charts".

## 0.3.1-20260911

- Correction: an earlier attempt at this release used a 4-component date-based version (`2026.09.11.01`), which Kiosk Satellite's plugin manifest validator rejects (`FormatException: Invalid plugin ID or version`) — it requires 3-component semver, optionally with a `-suffix`. That broken release has been removed; this one embeds the date as a semver prerelease suffix instead. No other changes since v0.3.0.

## 0.3.0

- WebView dashboard responsiveness now also reports a rolling ~4-minute p95 and peak (in ha-paneld's own "ms of main-thread time per second" units) and a 24h renderer-reload count, ported from ha-paneld's "Dashboard responsiveness" detail card. Not ported: tap response, time-to-interactive, and the "likely cause" classifier — those need WebView-internal JS instrumentation SDK 1 doesn't expose.
- New top-processes-by-CPU/RAM diagnostic: a single root `/proc/stat` + `/proc/<pid>/stat` scan, ranked by jiffy delta (CPU) and resident pages (RAM), shown as top-3 CPU and top-1 RAM in the status line. Ported from ha-paneld's PerfReader.sampleTop, simplified to use the kernel's truncated `comm` name instead of a second `/proc/<pid>/cmdline` round-trip. Verified against a real 382-process dump on production hardware.

## 0.2.0

- System CPU % and temperature via Kiosk Satellite's own `getStats` read command (declares `host.read`) — no root.
- WebView dashboard responsiveness: a root `/proc` probe for the Chromium renderer's `CrRendererMain` thread, ported from ha-paneld's PerfReader, classified smooth/occasional/janky by %-of-one-core busy time. Verified end-to-end against a live dashboard.
- Both refresh on their own 10-second timer, independent of the tuning controls, and appear in the same status line.
- Status text only — no chart/history (blocked on [jxlarrea/kiosk-satellite-plugin-hello-world#1](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/1)) and no real Home Assistant entity (blocked on [#2](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/2)).

## 0.1.0

- CPU governor tuning as three tiers (Auto, Performance, Efficiency) across every `cpufreq` policy cluster, resolved from the panel's own available governor list.
- GPU governor tuning with the same three tiers, on panels that expose a `devfreq` GPU node.
- Android system Battery Saver toggle (`settings put global low_power`), independent of the CPU/GPU governor.
- Advanced CPU frequency capping: a min and max percentage of each cluster's own hardware frequency range, so a big.LITTLE panel with different per-cluster ceilings is capped correctly on each cluster.
- Simulation mode for testing without root or hardware.
- `detect` and `restoreDefaults` actions.
