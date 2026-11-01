package net.sterling.automod.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SterlingConnectLink(String serverIp, int serverPort, URI manifestUri) {
    private static final Pattern LINK_PATTERN = Pattern.compile("sterling://connect\\?[^\\s<>\"']+");
    private static final Pattern REGISTRY_MISMATCH_PATTERN = Pattern.compile(
            "(?i)Received\\s+\\d+\\s+(?:registry\\s+entries|registries)\\s+that are unknown to this client");
    private static final int DEFAULT_LOCAL_MANIFEST_PORT = 8080;

    public static Optional<SterlingConnectLink> parse(String message) {
        Matcher matcher = LINK_PATTERN.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String linkText = matcher.group();
        while (!linkText.isEmpty() && ".,;)]}".indexOf(linkText.charAt(linkText.length() - 1)) >= 0) {
            linkText = linkText.substring(0, linkText.length() - 1);
        }

        try {
            URI link = URI.create(linkText);
            Map<String, String> query = parseQuery(link.getRawQuery());
            String host = query.get("ip");
            String manifest = query.get("manifest");
            int port = Integer.parseInt(query.getOrDefault("port", "25565"));
            if (!"sterling".equalsIgnoreCase(link.getScheme())
                    || !"connect".equalsIgnoreCase(link.getHost())
                    || host == null || host.isBlank()
                    || port < 1 || port > 65535
                    || manifest == null) {
                return Optional.empty();
            }

            URI manifestUri = URI.create(manifest);
            if (!"http".equalsIgnoreCase(manifestUri.getScheme())
                    && !"https".equalsIgnoreCase(manifestUri.getScheme())) {
                return Optional.empty();
            }
            return Optional.of(new SterlingConnectLink(host, port, manifestUri));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static boolean isRegistryMismatch(String message) {
        return message != null && REGISTRY_MISMATCH_PATTERN.matcher(message).find();
    }

    public static Optional<SterlingConnectLink> forRegistryMismatch(String serverAddress) {
        return forRegistryMismatch(serverAddress, null);
    }

    public static Optional<SterlingConnectLink> forRegistryMismatch(String serverAddress, URI knownManifestUri) {
        if (serverAddress == null || serverAddress.isBlank()) {
            return Optional.empty();
        }

        try {
            URI serverUri = new URI("tcp://" + serverAddress);
            String host = serverUri.getHost();
            if (host == null || host.isBlank()) {
                return Optional.empty();
            }

            int serverPort = serverUri.getPort() < 0 ? 25565 : serverUri.getPort();
            URI manifestUri = knownManifestUri;
            if (manifestUri == null) {
                if (!"localhost".equalsIgnoreCase(host)) {
                    return Optional.empty();
                }
                manifestUri = new URI("http", null, host, DEFAULT_LOCAL_MANIFEST_PORT,
                        "/manifest.json", null, null);
            }
            return Optional.of(new SterlingConnectLink(host, serverPort, manifestUri));
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }
}
