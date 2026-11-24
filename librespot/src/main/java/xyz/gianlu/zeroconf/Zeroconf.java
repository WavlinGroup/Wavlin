/*
 * Minimal Zeroconf shim for Android.
 *
 * Uses Android's NsdManager (API 16+) to register mDNS services.
 * The API surface matches exactly what ZeroconfServer.java calls:
 *
 *   zeroconf = new Zeroconf();
 *   zeroconf.setLocalHostName(hostname);
 *   zeroconf.setUseIpv4(true).setUseIpv6(false);
 *   zeroconf.addNetworkInterfaces(nics);
 *   zeroconf.announce(service);
 *   zeroconf.close();
 *
 * Context injection: Zeroconf.init(context) must be called once at app start
 * (e.g. in Application.onCreate) before any ZeroconfServer is created.
 * If not called, announce() is a no-op and logs a warning — discovery simply
 * won't work, which is acceptable for non-LAN-discovery flows.
 */
package xyz.gianlu.zeroconf;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Zeroconf implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Zeroconf.class);

    // Set once from application context; safe for the lifetime of the process.
    private static volatile Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    // Registered registrations — kept so we can unregister on close().
    private final List<NsdManager.RegistrationListener> listeners = new ArrayList<>();
    private NsdManager nsdManager;

    // These setters are fluent and called by ZeroconfServer — just return this.
    public Zeroconf setLocalHostName(String hostname) {
        return this;
    }

    public Zeroconf setUseIpv4(boolean v) {
        return this;
    }

    public Zeroconf setUseIpv6(boolean v) {
        return this;
    }

    public Zeroconf addNetworkInterfaces(List<NetworkInterface> nics) {
        return this;
    }

    public void announce(Service service) {
        Context ctx = appContext;
        if (ctx == null) {
            LOGGER.warn("Zeroconf.init() was not called — skipping mDNS announce for '{}'", service.name);
            return;
        }

        if (nsdManager == null) {
            nsdManager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        }

        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(service.name);
        // NsdManager expects e.g. "_spotify-connect._tcp." — build that from the bare type name.
        info.setServiceType(normalizeType(service.type));
        info.setPort(service.port);

        // Encode TXT records as byte[] attributes (API 21+)
        for (Map.Entry<String, String> e : service.getText().entrySet()) {
            try {
                info.setAttribute(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("Could not set TXT attribute '{}': {}", e.getKey(), ex.getMessage());
            }
        }

        NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            @Override public void onRegistrationFailed(NsdServiceInfo si, int code) {
                LOGGER.error("mDNS registration failed for '{}', error {}", si.getServiceName(), code);
            }
            @Override public void onUnregistrationFailed(NsdServiceInfo si, int code) {
                LOGGER.warn("mDNS unregistration failed for '{}', error {}", si.getServiceName(), code);
            }
            @Override public void onServiceRegistered(NsdServiceInfo si) {
                LOGGER.info("mDNS registered: {}", si.getServiceName());
            }
            @Override public void onServiceUnregistered(NsdServiceInfo si) {
                LOGGER.info("mDNS unregistered: {}", si.getServiceName());
            }
        };

        listeners.add(listener);
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    @Override
    public void close() {
        if (nsdManager == null) return;
        for (NsdManager.RegistrationListener l : listeners) {
            try {
                nsdManager.unregisterService(l);
            } catch (Exception e) {
                LOGGER.warn("Error unregistering mDNS service: {}", e.getMessage());
            }
        }
        listeners.clear();
    }

    /**
     * Convert the bare type name from ZeroconfServer (e.g. "spotify-connect") to the
     * fully-qualified service type NsdManager expects: "_spotify-connect._tcp."
     *
     * Handles inputs that are already qualified so we don't double-prefix.
     */
    private static String normalizeType(String type) {
        if (type == null || type.isEmpty()) return "_spotify-connect._tcp.";
        // Already looks qualified: "_foo._tcp" or "_foo._tcp."
        if (type.startsWith("_") && type.contains("._")) {
            return type.endsWith(".") ? type : type + ".";
        }
        // Bare name like "spotify-connect"
        String base = type.startsWith("_") ? type : "_" + type;
        return base + "._tcp.";
    }
}
