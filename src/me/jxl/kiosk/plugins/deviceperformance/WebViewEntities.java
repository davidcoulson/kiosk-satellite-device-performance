// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.deviceperformance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure mapping from this plugin's latest diagnostics readings to the set
 * of SDK 1 sensor entities it should publish — no PluginHost calls, so
 * it's unit-testable; see test/WebViewEntitiesTest.java. Unlike Network
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
final class WebViewEntities {
    private WebViewEntities() {}

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

    /** The smooth/occasional/janky classification, as a text sensor. Kept
     *  separate from {@link #compute}'s numeric sensors because it's a
     *  String, not a Double — and published at all so the verdict shows up
     *  in the host's own Readings list alongside the numbers it summarizes,
     *  rather than only existing in status text. */
    static final String VERDICT_KEY = "webview_verdict";

    /** The status tile severity for a verdict: smooth is the good case,
     *  occasional is worth knowing about, janky is the panel visibly
     *  failing to keep up. An unrecognised or absent verdict is neutral
     *  rather than alarming -- no measurement yet is not a fault, and the
     *  first window takes a while on a slow panel. */
    static String verdictLevel(String verdict) {
        if ("smooth".equals(verdict)) return "on";
        if ("occasional".equals(verdict)) return "warn";
        if ("janky".equals(verdict)) return "off";
        return "";
    }
    static final String VERDICT_NAME = "WebView responsiveness";

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
        // One decimal in the host's Readings list: these move enough that
        // whole percent hides real change, and the default is 0.
        m.put("accuracyDecimals", 1);
        return m;
    }

    private static Map<String, Object> msPerSMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("unit", "ms/s");
        m.put("stateClass", "measurement");
        m.put("accuracyDecimals", 1);
        return m;
    }

    private static Map<String, Object> countMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("stateClass", "measurement");
        return m;
    }
}
