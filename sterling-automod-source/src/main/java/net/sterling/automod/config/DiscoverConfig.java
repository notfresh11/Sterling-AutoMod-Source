package net.sterling.automod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiscoverConfig {
    private static final String DEFAULT_CATALOG_URL = "https://sterling-discover.cojocarumario80.workers.dev";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private String catalogUrl = DEFAULT_CATALOG_URL;

    public static DiscoverConfig load(Path path) throws IOException {
        DiscoverConfig config = new DiscoverConfig();
        if (Files.notExists(path)) {
            config.save(path);
            return config;
        }
        JsonObject data;
        try {
            data = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Sterling AutoMod Discover configuration is invalid.", exception);
        }
        if (data.has("catalogUrl") && data.get("catalogUrl").isJsonPrimitive()) {
            String catalogUrl = data.get("catalogUrl").getAsString();
            if (!catalogUrl.isBlank()) {
                config.setCatalogUrl(catalogUrl);
            }
        }
        config.validate();
        return config;
    }

    public void save(Path path) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        JsonObject data = new JsonObject();
        data.addProperty("catalogUrl", catalogUrl);
        Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
    }

    public void validate() {
        if (catalogUrl.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(catalogUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Discover catalogUrl must be a valid HTTPS base URL.", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw new IllegalArgumentException(
                    "Discover catalogUrl must use HTTPS and must not contain credentials, path, query, or fragment.");
        }
    }

    public String getCatalogUrl() {
        return catalogUrl;
    }

    public void setCatalogUrl(String catalogUrl) {
        this.catalogUrl = catalogUrl == null ? "" : catalogUrl.strip().replaceAll("/+$", "");
    }
}
