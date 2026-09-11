# Changelog

## 0.1.0

- CPU governor tuning as three tiers (Auto, Performance, Efficiency) across every `cpufreq` policy cluster, resolved from the panel's own available governor list.
- GPU governor tuning with the same three tiers, on panels that expose a `devfreq` GPU node.
- Android system Battery Saver toggle (`settings put global low_power`), independent of the CPU/GPU governor.
- Advanced CPU frequency capping: a min and max percentage of each cluster's own hardware frequency range, so a big.LITTLE panel with different per-cluster ceilings is capped correctly on each cluster.
- Simulation mode for testing without root or hardware.
- `detect` and `restoreDefaults` actions.
