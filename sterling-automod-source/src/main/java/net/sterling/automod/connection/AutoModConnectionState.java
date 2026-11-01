package net.sterling.automod.connection;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AutoModConnectionState {
    private static volatile String lastServerAddress;
    private static volatile URI lastManifestUri;
    private static final Map<String, URI> DISCOVER_MANIFESTS = new LinkedHashMap<>();
    private static final Map<String, Boolean> DISCOVER_VERIFICATION = new LinkedHashMap<>();

    private AutoModConnectionState() {
    }

    public static void rememberServerAddress(String address) {
        lastServerAddress = address;
        lastManifestUri = getDiscoveredManifestUri(address);
    }

    public static synchronized void clearDiscoveredManifests() {
        DISCOVER_MANIFESTS.clear();
        DISCOVER_VERIFICATION.clear();
    }

    public static synchronized void rememberDiscoveredManifest(String address, String manifestUrl) {
        URI manifestUri;
        try {
            manifestUri = URI.create(manifestUrl);
        } catch (IllegalArgumentException exception) {
            return;
        }
        if (address == null || address.isBlank() || manifestUri.getHost() == null
                || manifestUri.getUserInfo() != null
                || (!"http".equalsIgnoreCase(manifestUri.getScheme())
                && !"https".equalsIgnoreCase(manifestUri.getScheme()))) {
            return;
        }
        if (DISCOVER_MANIFESTS.size() >= 200) {
            DISCOVER_MANIFESTS.clear();
        }
        DISCOVER_MANIFESTS.put(normalizeAddress(address), manifestUri);
    }

    public static synchronized void rememberDiscoveryVerification(String address, boolean verified) {
        if (address == null || address.isBlank()) {
            return;
        }
        DISCOVER_VERIFICATION.put(normalizeAddress(address), verified);
    }

    public static synchronized Boolean getDiscoveryVerification(String address) {
        return address == null ? null : DISCOVER_VERIFICATION.get(normalizeAddress(address));
    }

    public static synchronized void clearDiscoveryVerification() {
        DISCOVER_VERIFICATION.clear();
    }

    private static synchronized URI getDiscoveredManifestUri(String address) {
        return address == null ? null : DISCOVER_MANIFESTS.get(normalizeAddress(address));
    }

    private static String normalizeAddress(String address) {
        return address.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public static String getLastServerAddress() {
        return lastServerAddress;
    }

    public static URI getLastManifestUri() {
        return lastManifestUri;
    }
}
