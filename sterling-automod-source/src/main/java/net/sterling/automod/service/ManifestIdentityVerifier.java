package net.sterling.automod.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

final class ManifestIdentityVerifier {
    private static final String ALGORITHM = "Ed25519";

    private ManifestIdentityVerifier() {
    }

    static VerifiedIdentity verify(JsonObject manifest) throws IOException {
        try {
            if (!manifest.has("identityAlgorithm")
                    || !ALGORITHM.equals(manifest.get("identityAlgorithm").getAsString())
                    || !manifest.has("identityPublicKey")
                    || !manifest.has("manifestSignature")) {
                throw new IOException("The Bridge manifest is unsigned. Update Sterling Bridge before installing.");
            }
            String encodedPublicKey = manifest.get("identityPublicKey").getAsString();
            byte[] publicKeyBytes = Base64.getDecoder().decode(encodedPublicKey);
            byte[] signatureBytes = Base64.getDecoder().decode(manifest.get("manifestSignature").getAsString());
            PublicKey publicKey = KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            JsonObject payload = manifest.deepCopy();
            payload.remove("manifestSignature");
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(canonicalBytes(payload));
            if (!signature.verify(signatureBytes)) {
                throw new IOException("Bridge manifest signature is invalid; no files were installed.");
            }
            String fingerprint = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKeyBytes));
            return new VerifiedIdentity(encodedPublicKey, fingerprint);
        } catch (IllegalArgumentException | GeneralSecurityException | IllegalStateException
                 | NullPointerException exception) {
            throw new IOException("Bridge manifest identity or signature is invalid.", exception);
        }
    }

    static String fingerprint(String encodedPublicKey) throws IOException {
        try {
            byte[] publicKeyBytes = Base64.getDecoder().decode(encodedPublicKey);
            KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(publicKeyBytes));
        } catch (IllegalArgumentException | GeneralSecurityException | IllegalStateException
                 | NullPointerException exception) {
            throw new IOException("Bridge manifest identity key is invalid.", exception);
        }
    }

    private static byte[] canonicalBytes(JsonObject object) {
        return new com.google.gson.Gson().toJson(sortRecursively(object)).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonElement sortRecursively(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            Map<String, JsonElement> properties = new TreeMap<>();
            element.getAsJsonObject().entrySet()
                    .forEach(entry -> properties.put(entry.getKey(), entry.getValue()));
            properties.forEach((key, value) -> sorted.add(key, sortRecursively(value)));
            return sorted;
        }
        if (element.isJsonArray()) {
            var sorted = new com.google.gson.JsonArray();
            element.getAsJsonArray().forEach(value -> sorted.add(sortRecursively(value)));
            return sorted;
        }
        return element.deepCopy();
    }

    record VerifiedIdentity(String publicKey, String fingerprint) {
    }
}
