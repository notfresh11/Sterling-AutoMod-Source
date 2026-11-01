package net.sterling.automod.service;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

public final class ServerIdentityTrustStore {
    private final Path path;
    private final Gson gson = new Gson();

    public ServerIdentityTrustStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public synchronized Assessment assess(
            String address,
            String serverName,
            String encodedPublicKey
    ) throws IOException {
        String key = normalizeAddress(address);
        String fingerprint = ManifestIdentityVerifier.fingerprint(encodedPublicKey);
        TrustedServers trustedServers = read();
        TrustedIdentity existing = trustedServers.servers.get(key);
        TrustStatus status;
        String previousFingerprint = null;
        if (existing == null) {
            status = trustedServers.servers.values().stream()
                    .anyMatch(identity -> identity.fingerprint.equals(fingerprint))
                    ? TrustStatus.TRUSTED_KEY_NEW_ADDRESS
                    : TrustStatus.FIRST_USE;
        } else if (existing.fingerprint.equals(fingerprint)) {
            status = TrustStatus.TRUSTED;
        } else {
            status = TrustStatus.IDENTITY_CHANGED;
            previousFingerprint = existing.fingerprint;
        }
        return new Assessment(key, serverName, encodedPublicKey, fingerprint, previousFingerprint, status);
    }

    public synchronized void trust(Assessment assessment) throws IOException {
        String actualFingerprint = ManifestIdentityVerifier.fingerprint(assessment.publicKey());
        if (!actualFingerprint.equals(assessment.fingerprint())) {
            throw new IOException("The server identity changed while it was being approved.");
        }
        TrustedServers trustedServers = read();
        TrustedIdentity current = trustedServers.servers.get(assessment.address());
        if (assessment.status() == TrustStatus.TRUSTED
                && current != null && current.fingerprint.equals(actualFingerprint)) {
            return;
        }
        trustedServers.servers.put(assessment.address(), new TrustedIdentity(
                actualFingerprint, assessment.publicKey(), assessment.serverName(), Instant.now().toString()));
        save(trustedServers);
    }

    public synchronized boolean isTrusted(String address, String fingerprint) throws IOException {
        TrustedIdentity identity = read().servers.get(normalizeAddress(address));
        if (identity == null || !identity.fingerprint.equals(fingerprint)) {
            return false;
        }
        return ManifestIdentityVerifier.fingerprint(identity.publicKey).equals(fingerprint);
    }

    private TrustedServers read() throws IOException {
        if (Files.notExists(path)) {
            return new TrustedServers();
        }
        try {
            TrustedServers trustedServers = gson.fromJson(
                    Files.readString(path, StandardCharsets.UTF_8), TrustedServers.class);
            if (trustedServers == null || trustedServers.schemaVersion != 1 || trustedServers.servers == null) {
                throw new IOException("The saved Sterling server trust store is empty or invalid.");
            }
            for (Map.Entry<String, TrustedIdentity> entry : trustedServers.servers.entrySet()) {
                TrustedIdentity identity = entry.getValue();
                if (identity == null || identity.publicKey == null || identity.fingerprint == null
                        || identity.serverName == null || identity.trustedAt == null
                        || !ManifestIdentityVerifier.fingerprint(identity.publicKey).equals(identity.fingerprint)) {
                    throw new IOException("The saved Sterling server trust store contains an invalid identity.");
                }
            }
            return trustedServers;
        } catch (JsonParseException | IllegalStateException | NullPointerException exception) {
            throw new IOException("Could not read the saved Sterling server trust store.", exception);
        }
    }

    private void save(TrustedServers trustedServers) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".trusted-servers-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(trustedServers), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String normalizeAddress(String address) throws IOException {
        if (address == null || address.isBlank()) {
            throw new IOException("The Minecraft server address is missing.");
        }
        return address.strip().toLowerCase(Locale.ROOT);
    }

    public enum TrustStatus {
        FIRST_USE,
        TRUSTED,
        TRUSTED_KEY_NEW_ADDRESS,
        IDENTITY_CHANGED
    }

    public record Assessment(
            String address,
            String serverName,
            String publicKey,
            String fingerprint,
            String previousFingerprint,
            TrustStatus status
    ) {
    }

    private static final class TrustedServers {
        private int schemaVersion = 1;
        private Map<String, TrustedIdentity> servers = new LinkedHashMap<>();
    }

    private static final class TrustedIdentity {
        private String fingerprint;
        private String publicKey;
        private String serverName;
        private String trustedAt;

        private TrustedIdentity(String fingerprint, String publicKey, String serverName, String trustedAt) {
            this.fingerprint = fingerprint;
            this.publicKey = publicKey;
            this.serverName = serverName;
            this.trustedAt = trustedAt;
        }
    }
}
