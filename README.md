# CPU Performance Mode for Kiosk Satellite

Tune CPU and GPU governors, Android's system battery saver, and CPU frequency limits on rooted Kiosk Satellite panels — no native code, every control is a root shell command over standard Linux `cpufreq`/`devfreq` sysfs nodes.

## Requirements

- Kiosk Satellite with **plugin SDK 1 support**.
- A rooted panel (e.g. Magisk). None of these controls are reachable without root on any panel checked so far — `scaling_governor`, `scaling_min_freq`/`scaling_max_freq` and the GPU `devfreq` governor are all root-only writes, unlike this project's [Rockchip LED Control](https://github.com/davidcoulson/kiosk-satellite-rockchip-led-control) plugin, which has a root-free path on some panels.

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

## Why no overclocking or undervolting

This plugin deliberately does not attempt to raise a CPU past its hardware maximum or adjust voltage. Checked directly against real hardware: `cpuinfo_max_freq` already equals the top entry in `scaling_available_frequencies` — there's no headroom in the vendor's own frequency table to exceed, and the only voltage-related sysfs nodes present are regulator tracing/debug hooks, not writable voltage tables. Real over/underclocking needs a custom kernel exposing those controls, which stock Rockchip vendor kernels don't. It would also be genuinely risky (thermal/electrical stress) on an always-on, wall-mounted panel for no benefit on a dashboard workload.

## Build and test

```sh
export JAVA_HOME=/path/to/jdk
python3 tools/test.py
python3 tools/build.py
```

`tools/test.py` runs device-free unit tests of the governor-resolution and frequency-percent math (including the per-cluster hardware-max scenario from a real big.LITTLE panel) — it proves the logic, not device compatibility. `tools/build.py` produces the ZIP, checksum and manifest in `dist/`.

## Publishing and handoff

Apache-2.0. The plugin ID is `cpu-performance-mode`. See [jxlarrea/kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the SDK 1 documentation this plugin was built against.
