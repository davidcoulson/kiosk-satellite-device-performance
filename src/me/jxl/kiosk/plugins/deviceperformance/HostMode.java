// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.deviceperformance;

import java.util.Map;

/**
 * Pure reading of the host's `getHostApi` answer — no PluginHost calls,
 * so it's unit-testable; see test/HostModeTest.java.
 *
 * Kiosk Satellite can run as an agent: a headless management install on
 * a projector or media box, with no dashboard and no WebView. There is
 * nothing there for the WebView diagnostics to measure, so every one of
 * them would sit at "unknown" in Home Assistant forever. The host says
 * which it is in `getHostApi`'s `agent` field, which needs no capability
 * to ask for.
 */
final class HostMode {
    private HostMode() {}

    /** True only for an explicit `agent: true`. Hosts from before agent
     *  mode omit the field, and anything that isn't a clear yes -- a
     *  failed command, a null answer, a non-boolean -- is a panel, which
     *  is exactly how this plugin behaved before it asked. */
    static boolean agent(Object hostApi) {
        return hostApi instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) hostApi).get("agent"));
    }
}
