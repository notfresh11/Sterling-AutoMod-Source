package net.sterling.automod.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

public final class SterlingDiscoverService {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public List<ServerListing> fetchServers(String catalogUrl) throws IOException, InterruptedException {
        if (catalogUrl == null || catalogUrl.isBlank()) {
            throw new IOException("Set catalogUrl in config/sterling/automod-discover.json first.");
        }
        URI endpoint;
        try {
            endpoint = URI.create(catalogUrl.strip().replaceAll("/+$", "") + "/v1/servers");
        } catch (IllegalArgumentException exception) {
            throw new IOException("Sterling Discover catalog URL is invalid.", exception);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IOException("Sterling Discover catalog must be a valid HTTPS URL.");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, info -> new LimitedBodySubscriber(MAX_RESPONSE_BYTES));
        if (response.statusCode() != 200) {
            throw new IOException("Sterling Discover returned HTTP " + response.statusCode() + ".");
        }
        return parseServers(new String(response.body(), StandardCharsets.UTF_8));
    }

    static List<ServerListing> parseServers(String body) throws IOException {
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            throw new IOException("Sterling Discover response is too large.");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Sterling Discover returned invalid JSON.", exception);
        }
        JsonElement serversElement = root.get("servers");
        if (serversElement == null || !serversElement.isJsonArray()) {
            throw new IOException("Sterling Discover response is missing the servers list.");
        }
        JsonArray servers = serversElement.getAsJsonArray();
        if (servers.size() > 200) {
            throw new IOException("Sterling Discover returned too many server entries.");
        }
        List<ServerListing> listings = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement element : servers) {
            if (!element.isJsonObject()) {
                throw new IOException("Sterling Discover returned an invalid server entry.");
            }
            JsonObject server = element.getAsJsonObject();
            try {
                String id = requiredString(server, "id");
                String name = requiredString(server, "name");
                String address = requiredString(server, "address");
                int port = server.get("port").getAsInt();
                String minecraftVersion = requiredString(server, "minecraftVersion");
                String loaderType = requiredString(server, "loaderType").toLowerCase(Locale.ROOT);
                String loaderVersion = requiredString(server, "loaderVersion");
                String verificationStatus = server.has("verificationStatus")
                        ? requiredString(server, "verificationStatus") : "domain_verified";
                String manifestUrl = optionalManifestUrl(server);
                UUID.fromString(id);
                if (!ids.add(id) || name.length() > 80 || hasControlCharacters(name)
                        || !validAddress(address) || port < 1 || port > 65535
                        || !validVersion(minecraftVersion) || !validVersion(loaderVersion)
                        || !Set.of("fabric", "forge").contains(loaderType)
                        || !Set.of("domain_verified", "unverified").contains(verificationStatus)) {
                    throw new IOException("Sterling Discover returned an invalid server entry.");
                }
                listings.add(new ServerListing(
                        id, name, address, port, minecraftVersion, loaderType, loaderVersion,
                        verificationStatus, manifestUrl));
            } catch (RuntimeException exception) {
                throw new IOException("Sterling Discover returned an invalid server entry.", exception);
            }
        }
        return List.copyOf(listings);
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - output.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Sterling Discover response is too large."));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }

    private static String requiredString(JsonObject server, String property) throws IOException {
        JsonElement value = server.get(property);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Server listing is missing " + property + ".");
        }
        String result = value.getAsString().strip();
        if (result.isBlank() || result.length() > 253) {
            throw new IOException("Server listing contains an invalid " + property + ".");
        }
        return result;
    }

    private static String optionalManifestUrl(JsonObject server) throws IOException {
        JsonElement value = server.get("manifestUrl");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Sterling Discover returned an invalid manifest URL.");
        }
        String manifestUrl = value.getAsString().strip();
        if (manifestUrl.isBlank() || manifestUrl.length() > 2048) {
            throw new IOException("Sterling Discover returned an invalid manifest URL.");
        }
        URI uri;
        try {
            uri = URI.create(manifestUrl);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Sterling Discover returned an invalid manifest URL.", exception);
        }
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || (!path.endsWith("/manifest.json") && !path.endsWith(".mrpack"))) {
            throw new IOException("Sterling Discover returned an invalid manifest URL.");
        }
        return manifestUrl;
    }

    private static boolean validAddress(String address) {
        if (address.contains("://") || address.contains("/") || address.contains("\\")
                || address.contains("@") || address.contains("?") || address.contains("#")
                || address.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        return address.startsWith("[")
                ? address.endsWith("]") && address.length() > 2
                : !address.startsWith(":") && !address.endsWith(":");
    }

    private static boolean validVersion(String version) {
        return version.length() <= 64 && version.matches("[A-Za-z0-9._-]+");
    }

    private static boolean hasControlCharacters(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    public record ServerListing(
            String id,
            String name,
            String address,
            int port,
            String minecraftVersion,
            String loaderType,
            String loaderVersion,
            String verificationStatus,
            String manifestUrl
    ) {
    }
}
