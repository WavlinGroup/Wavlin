/*
 * Minimal Zeroconf shim for Android.
 * Replaces xyz.gianlu.zeroconf (unavailable on Android) with an NsdManager-backed
 * implementation that exposes the exact API surface ZeroconfServer.java uses.
 */
package xyz.gianlu.zeroconf;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Service {
    final String name;
    final String type;
    final int port;
    private Map<String, String> txt = Collections.emptyMap();

    public Service(String name, String type, int port) {
        this.name = name;
        this.type = type;
        this.port = port;
    }

    public void setText(Map<String, String> txt) {
        this.txt = new HashMap<>(txt);
    }

    public Map<String, String> getText() {
        return Collections.unmodifiableMap(txt);
    }
}
