# Changelog

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
