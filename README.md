# CPU Performance Mode for Kiosk Satellite

Tune CPU and GPU governors, Android's system battery saver, and CPU frequency limits on rooted Kiosk Satellite panels — no native code, every control is a root shell command over standard Linux `cpufreq`/`devfreq` sysfs nodes. Also reports live diagnostics: system CPU/RAM/temp and WebView dashboard responsiveness.

## Requirements

- Kiosk Satellite with **plugin SDK 1 support**.
- A rooted panel (e.g. Magisk) for the tuning controls. `scaling_governor`, `scaling_min_freq`/`scaling_max_freq` and the GPU `devfreq` governor are all root-only writes on every panel checked so far, unlike this project's [Rockchip LED Control](https://github.com/davidcoulson/kiosk-satellite-rockchip-led-control) plugin, which has a root-free path on some panels. The WebView diagnostic also needs root (reading another app's `/proc/<pid>` entries needs it on modern Android); system CPU/RAM/temp needs none.

## Install and use

1. Wait for a stable GitHub release and its GitHub Actions build to complete.
2. Open **Plugin Manager > Add plugin**, paste this repository URL, review the manifest and README and choose **Trust and install**.
3. Enable **CPU Performance Mode** on its entry row and open the subpage.
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

## Diagnostics (status text — not Home Assistant entities yet)

Refreshed every 10 seconds, independent of the tuning controls above, and shown in the same status line:

| Reading | Mechanism | Root? |
| --- | --- | --- |
| System CPU % and temperature | Kiosk Satellite's own `getStats` read command | No |
| WebView dashboard responsiveness | A root `/proc` probe for the Chromium renderer's `CrRendererMain` thread — smooth / occasional / janky, by %-of-one-core busy time | Yes |

None of this becomes a real Home Assistant sensor entity today — SDK 1's `entities` capability only supports RGB lights. See [jxlarrea/kiosk-satellite-plugin-hello-world#2](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/2), and [#1](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/1) for the related chart-rendering gap (this plugin shows only the latest reading, not a history/graph, for the same reason).

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

`tools/test.py` runs device-free unit tests of the governor-resolution and frequency-percent math (including the per-cluster hardware-max scenario from a real big.LITTLE panel) plus the WebView jiffy-delta parsing (including a `/proc/pid/stat` line whose `comm` field itself contains a stray `)`, the classic bug in a naive port) — it proves the logic, not device compatibility. `tools/build.py` produces the ZIP, checksum and manifest in `dist/`.

## Publishing and handoff

Apache-2.0. The plugin ID is `cpu-performance-mode`. See [jxlarrea/kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the SDK 1 documentation this plugin was built against.
