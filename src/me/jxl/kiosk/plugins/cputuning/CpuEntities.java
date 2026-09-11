// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.cputuning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure mapping from this plugin's latest diagnostics readings to the set
 * of SDK 1 sensor entities it should publish — no PluginHost calls, so
 * it's unit-testable; see test/CpuEntitiesTest.java. Unlike Network
 * Diagnostics' NetworkEntities, every entity here is always structurally
 * present (a null state, not a missing entity, represents "not known
 * yet") since none of these readings depend on optional plugin settings
 * the way a configurable ping target does — so there's no removal-diffing
 * to do, just publish four sensors every diagnostics tick.
 *
 * Deliberately does NOT republish system CPU % or temperature: Kiosk
 * Satellite already exposes those as its own native `cpu`/`cpu_temp`
 * ESPHome entities (see app/lib/managers/btproxy/esp_entities.dart) from
 * the same `getStats` read command this plugin also uses — a second
 * sensor here would just be a confusing duplicate in Home Assistant.
 */
final class CpuEntities {
    private CpuEntities() {}

    static final class Entity {
        final String key;
        final String name;
        final Map<String, Object> metadata;
        final Double state;

        Entity(String key, String name, Map<String, Object> metadata, Double state) {
            this.key = key;
            this.name = name;
            this.metadata = metadata;
            this.state = state;
        }
    }

    static List<Entity> compute(Double webViewBusyPercent, Double webViewP95MsPerS, Double webViewPeakMsPerS,
                                 Integer rendererReloads24h) {
        List<Entity> entities = new ArrayList<>();
        entities.add(new Entity("webview_busy_percent", "WebView busy", percentMetadata(), webViewBusyPercent));
        entities.add(new Entity("webview_p95_ms_per_s", "WebView p95", msPerSMetadata(), webViewP95MsPerS));
        entities.add(new Entity("webview_peak_ms_per_s", "WebView peak", msPerSMetadata(), webViewPeakMsPerS));
        entities.add(new Entity("renderer_reloads_24h", "Renderer reloads (24h)", countMetadata(),
            rendererReloads24h == null ? null : rendererReloads24h.doubleValue()));
        return entities;
    }

    private static Map<String, Object> percentMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("unit", "%");
        m.put("stateClass", "measurement");
        return m;
    }

    private static Map<String, Object> msPerSMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("unit", "ms/s");
        m.put("stateClass", "measurement");
        return m;
    }

    private static Map<String, Object> countMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("stateClass", "measurement");
        return m;
    }
}
