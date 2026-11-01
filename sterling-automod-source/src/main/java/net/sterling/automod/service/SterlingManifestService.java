package net.sterling.automod.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.sterling.automod.util.SterlingConnectLink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class SterlingManifestService {
    private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MOD_BYTES = 200 * 1024 * 1024;
    private static final int MAX_MOD_COUNT = 512;
    private static final int MAX_MODPACK_BYTES = 1024 * 1024 * 1024;
    private static final long MAX_TOTAL_MOD_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_DOWNLOAD_REDIRECTS = 5;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final int MAX_MOD_DOWNLOAD_URLS = 5;
    private static final String MODS_DIRECTORY = "mods/";
    private static final String OVERRIDES_MODS_DIRECTORY = "overrides/mods/";
    private static final String CLIENT_OVERRIDES_MODS_DIRECTORY = "client-overrides/mods/";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Gson gson = new Gson();

    public Path downloadAndCreatePack(
            SterlingConnectLink link,
            Path outputDirectory,
            ClientProfile clientProfile,
            ProgressListener progress
    ) throws Exception {
        validateClientProfile(clientProfile);
        SterlingManifest manifest = loadManifest(link, clientProfile);

        Files.createDirectories(outputDirectory);
        Path temporaryDirectory = Files.createTempDirectory(outputDirectory, ".sterling-download-");
        try {
            List<DownloadedMod> mods = downloadMods(
                    manifest, temporaryDirectory, progress, new DownloadCancellation());
            Path packPath = uniquePackPath(outputDirectory, manifest.serverName);
            writePack(packPath, manifest, mods);
            return packPath;
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    public InstallResult downloadAndInstallMods(
            SterlingConnectLink link,
            Path modsDirectory,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore,
            ProgressListener progress
    ) throws Exception {
        return downloadAndInstallMods(link, modsDirectory, clientProfile, identityTrustStore,
                null, null, progress);
    }

    public InstallResult downloadAndInstallMods(
            SterlingConnectLink link,
            Path modsDirectory,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore,
            String expectedManifestSignature,
            String expectedPackSha256,
            ProgressListener progress
    ) throws Exception {
        return downloadAndInstallMods(link, modsDirectory, clientProfile, identityTrustStore,
                expectedManifestSignature, expectedPackSha256, progress, new DownloadCancellation());
    }

    public InstallResult downloadAndInstallMods(
            SterlingConnectLink link,
            Path modsDirectory,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore,
            String expectedManifestSignature,
            String expectedPackSha256,
            ProgressListener progress,
            DownloadCancellation cancellation
    ) throws Exception {
        validateClientProfile(clientProfile);
        cancellation.throwIfCancelled();
        URI packUri = link.manifestUri();
        validateHttpUri(packUri);
        String host = packUri.getHost().toLowerCase(Locale.ROOT);
        String urlPath = packUri.getPath().toLowerCase(Locale.ROOT);
        if (host.contains("curseforge") || host.contains("forgecdn") || urlPath.endsWith(".zip")) {
            throw new IOException("CurseForge modpacks are not supported yet. Use a direct Modrinth .mrpack link.");
        }
        if (urlPath.endsWith(".mrpack")) {
            throw new IOException("Direct modpack links cannot verify the server identity. "
                    + "Update Sterling Bridge and use its signed manifest link.");
        }
        if (host.contains("modrinth.com") && !urlPath.endsWith(".json")) {
            throw new IOException("This is not a direct Modrinth .mrpack download URL. Copy the link from its Download button.");
        }

        SterlingManifest manifest = loadManifest(link, clientProfile, cancellation);
        if (expectedManifestSignature != null
                && !expectedManifestSignature.equals(manifest.manifestSignature)) {
            throw new IOException("The server manifest changed after the preview. Review the changes again.");
        }
        String serverAddress = serverAddress(link);
        if (!identityTrustStore.isTrusted(serverAddress, manifest.identityFingerprint)) {
            throw new IOException("This server identity is not trusted for this instance. "
                    + "Review and approve the identity before installing.");
        }
        if (manifest.modpackDownloadLink != null && !manifest.modpackDownloadLink.isBlank()) {
            URI configuredPackUri;
            try {
                configuredPackUri = URI.create(manifest.modpackDownloadLink);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Bridge manifest contains an invalid Modrinth modpack URL.", exception);
            }
            validateHttpUri(configuredPackUri);
            if (!configuredPackUri.getPath().toLowerCase(Locale.ROOT).endsWith(".mrpack")) {
                throw new IOException("Bridge manifest modpack URL must point directly to a .mrpack file.");
            }
            return downloadAndInstallMrpack(
                    configuredPackUri, modsDirectory, clientProfile, expectedPackSha256, progress, cancellation);
        }
        Path normalizedModsDirectory = modsDirectory.toAbsolutePath().normalize();
        Path gameDirectory = normalizedModsDirectory.getParent();
        if (gameDirectory == null) {
            throw new IOException("The Minecraft mods folder must have a parent directory.");
        }

        Files.createDirectories(gameDirectory);
        Path temporaryDirectory = Files.createTempDirectory(gameDirectory, ".sterling-download-");
        try {
            List<DownloadedMod> mods = downloadMods(manifest, temporaryDirectory, progress, cancellation);
            cancellation.throwIfCancelled();
            return installMods(mods, normalizedModsDirectory, gameDirectory, cancellation);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    public InstallPreview createInstallPreview(
            SterlingConnectLink link,
            Path modsDirectory,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore
    ) throws Exception {
        SterlingManifest manifest = loadTrustedManifest(link, clientProfile, identityTrustStore);
        if (manifest.modpackDownloadLink != null && !manifest.modpackDownloadLink.isBlank()) {
            URI packUri = validateModpackUri(manifest.modpackDownloadLink);
            return new InstallPreview(
                    manifest.serverName,
                    sourceLabel(packUri),
                    manifest.minecraftVersion,
                    manifest.loaderVersion,
                    true,
                    manifest.manifestSignature,
                    null,
                    List.of(),
                    retainedModFiles(modsDirectory, List.of()),
                    manifest.mods.stream()
                            .filter(entry -> Boolean.FALSE.equals(entry.required))
                            .map(entry -> entry.fileName)
                            .toList());
        }
        List<PreviewItem> items = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        Path normalizedModsDirectory = modsDirectory.toAbsolutePath().normalize();
        for (SterlingManifest.ModEntry entry : manifest.mods) {
            validateModEntry(entry, manifest.schemaVersion != null && manifest.schemaVersion == 1);
            if (!fileNames.add(entry.fileName.toLowerCase(Locale.ROOT))) {
                throw new IOException("Manifest contains duplicate mod filenames: " + entry.fileName);
            }
            if (Boolean.FALSE.equals(entry.required)) {
                skipped.add(entry.fileName + " (optional)");
                continue;
            }
            URI source = URI.create(entry.downloadUrl);
            Path installedFile = safeInstalledModPath(normalizedModsDirectory, entry.fileName);
            PreviewAction action;
            if (Files.isRegularFile(installedFile)
                    && sha256(installedFile).equalsIgnoreCase(entry.sha256)) {
                action = PreviewAction.ALREADY_CURRENT;
            } else if (Files.exists(installedFile)) {
                if (!Files.isRegularFile(installedFile)) {
                    throw new IOException("A non-file already exists at the mod destination: " + installedFile);
                }
                action = PreviewAction.REPLACE;
            } else {
                action = PreviewAction.ADD;
            }
            items.add(new PreviewItem(entry.fileName, action, sourceLabel(source)));
        }
        return new InstallPreview(
                manifest.serverName,
                sourceLabel(link.manifestUri()),
                manifest.minecraftVersion,
                manifest.loaderVersion,
                false,
                manifest.manifestSignature,
                null,
                items,
                retainedModFiles(normalizedModsDirectory, items),
                skipped);
    }

    public InstallPreview inspectModpackPreview(
            SterlingConnectLink link,
            Path modsDirectory,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore,
            String expectedManifestSignature
    ) throws Exception {
        return inspectModpackPreview(link, modsDirectory, clientProfile, identityTrustStore,
                expectedManifestSignature, new DownloadCancellation());
    }

    public InstallPreview inspectModpackPreview(
            SterlingConnectLink link,
            Path modsDirectory,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore,
            String expectedManifestSignature,
            DownloadCancellation cancellation
    ) throws Exception {
        SterlingManifest manifest = loadTrustedManifest(link, clientProfile, identityTrustStore, cancellation);
        if (!manifest.manifestSignature.equals(expectedManifestSignature)) {
            throw new IOException("The server manifest changed after the preview. Review the changes again.");
        }
        URI packUri = validateModpackUri(manifest.modpackDownloadLink);
        Path normalizedModsDirectory = modsDirectory.toAbsolutePath().normalize();
        Path gameDirectory = normalizedModsDirectory.getParent();
        if (gameDirectory == null) {
            throw new IOException("The Minecraft mods folder must have a parent directory.");
        }
        Files.createDirectories(gameDirectory);
        Path temporaryDirectory = Files.createTempDirectory(gameDirectory, ".sterling-pack-preview-");
        try {
            Path archive = temporaryDirectory.resolve("server.mrpack");
            downloadBounded(packUri, archive, MAX_MODPACK_BYTES, cancellation);
            cancellation.throwIfCancelled();
            String packHash = sha256(archive, cancellation);
            return createModpackPreview(
                    manifest, packUri, archive, normalizedModsDirectory, clientProfile, packHash, cancellation);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private InstallPreview createModpackPreview(
            SterlingManifest manifest,
            URI packUri,
            Path archive,
            Path modsDirectory,
            ClientProfile clientProfile,
            String packHash,
            DownloadCancellation cancellation
    ) throws IOException {
        List<PreviewItem> items = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
                ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
                if (indexEntry == null || indexEntry.getSize() > MAX_MANIFEST_BYTES) {
                    throw new IOException("The Modrinth pack has no valid modrinth.index.json.");
                }
                ModrinthIndex index;
                try (InputStream input = zip.getInputStream(indexEntry)) {
                    index = gson.fromJson(new String(readBounded(input, MAX_MANIFEST_BYTES),
                            java.nio.charset.StandardCharsets.UTF_8), ModrinthIndex.class);
                } catch (RuntimeException exception) {
                    throw new IOException("The Modrinth pack index is invalid.", exception);
                }
                if (index == null || index.formatVersion != 1 || !"minecraft".equals(index.game)
                        || index.files == null || index.files.size() > MAX_MOD_COUNT) {
                    throw new IOException("The Modrinth pack is invalid or uses an unsupported format.");
                }
            validateModrinthDependencies(index, clientProfile);
            Map<String, PreviewItem> planned = new LinkedHashMap<>();
            Map<String, PreviewItem> bundledOverrides = new LinkedHashMap<>();
            Set<String> commonOverrideNames = new HashSet<>();
            Set<String> clientOverrideNames = new HashSet<>();
            long totalModBytes = 0;
            for (ModrinthFile file : index.files) {
                cancellation.throwIfCancelled();
                if (file == null) {
                    throw new IOException("The Modrinth pack contains an empty file entry.");
                }
                if (!isClientModPath(file.path)) {
                    continue;
                }
                validateModrinthEnvironment(file);
                String fileName = file.path.substring(MODS_DIRECTORY.length());
                ensureUniqueFileName(fileNames, fileName);
                if (isClientUnsupported(file)) {
                    skipped.add(file.path + " (unsupported client environment)");
                    continue;
                }
                validateModrinthFile(file);
                totalModBytes = addModBytes(totalModBytes, file.fileSize, "Modrinth pack");
                Path installedFile = safeInstalledModPath(modsDirectory, fileName);
                PreviewAction action = previewRemoteFileAction(installedFile, file);
                planned.put(fileName.toLowerCase(Locale.ROOT),
                        new PreviewItem(fileName, action, sourceLabel(packUri)));
            }

            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                cancellation.throwIfCancelled();
                ZipEntry entry = entries.nextElement();
                if (!isOverrideModEntry(entry)) {
                    continue;
                }
                String fileName = overrideFileName(entry);
                validateModFileName(fileName);
                String key = fileName.toLowerCase(Locale.ROOT);
                boolean clientOverride = entry.getName().startsWith(CLIENT_OVERRIDES_MODS_DIRECTORY);
                Set<String> overrideNames = clientOverride ? clientOverrideNames : commonOverrideNames;
                if (!overrideNames.add(key)) {
                    throw new IOException("The Modrinth pack contains duplicate override mods: " + fileName);
                }
                if (entry.getSize() <= 0 || entry.getSize() > MAX_MOD_BYTES) {
                    throw new IOException("Bundled mod " + fileName + " has an invalid size.");
                }
                totalModBytes = addModBytes(totalModBytes, entry.getSize(), "Modrinth pack");
                String hash;
                try (InputStream input = zip.getInputStream(entry)) {
                    hash = digest(input, "SHA-256", MAX_MOD_BYTES, cancellation);
                }
                fileNames.add(key);
                Path installedFile = safeInstalledModPath(modsDirectory, fileName);
                PreviewAction action = Files.isRegularFile(installedFile)
                        && sha256(installedFile).equalsIgnoreCase(hash)
                        ? PreviewAction.ALREADY_CURRENT
                        : Files.exists(installedFile) ? PreviewAction.REPLACE : PreviewAction.ADD;
                PreviewItem item = new PreviewItem(fileName, action, sourceLabel(packUri));
                if (clientOverride) {
                    bundledOverrides.put(key, item);
                } else {
                    bundledOverrides.putIfAbsent(key, item);
                }
            }
            planned.putAll(bundledOverrides);
            if (planned.size() > MAX_MOD_COUNT) {
                throw new IOException("The Modrinth pack contains too many client mods.");
            }
            items.addAll(planned.values());
            } catch (java.util.zip.ZipException exception) {
                throw new IOException("The Modrinth pack download is not a valid ZIP archive.", exception);
            }
            return new InstallPreview(
                    manifest.serverName,
                    sourceLabel(packUri),
                    manifest.minecraftVersion,
                    manifest.loaderVersion,
                    true,
                    manifest.manifestSignature,
                    packHash,
                    items,
                    retainedModFiles(modsDirectory, items),
                    skipped);
    }

    private SterlingManifest loadTrustedManifest(
            SterlingConnectLink link,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore
    ) throws Exception {
        SterlingManifest manifest = loadManifest(link, clientProfile);
        if (!identityTrustStore.isTrusted(serverAddress(link), manifest.identityFingerprint)) {
            throw new IOException("This server identity is not trusted for this instance. "
                    + "Review and approve the identity before installing.");
        }
        return manifest;
    }

    private SterlingManifest loadTrustedManifest(
            SterlingConnectLink link,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore,
            DownloadCancellation cancellation
    ) throws Exception {
        SterlingManifest manifest = loadManifest(link, clientProfile, cancellation);
        if (!identityTrustStore.isTrusted(serverAddress(link), manifest.identityFingerprint)) {
            throw new IOException("This server identity is not trusted for this instance. "
                    + "Review and approve the identity before installing.");
        }
        return manifest;
    }

    private static URI validateModpackUri(String value) throws IOException {
        URI packUri;
        try {
            packUri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Bridge manifest contains an invalid Modrinth modpack URL.", exception);
        }
        validateHttpUri(packUri);
        if (!"https".equalsIgnoreCase(packUri.getScheme())
                || packUri.getPath() == null
                || !packUri.getPath().toLowerCase(Locale.ROOT).endsWith(".mrpack")) {
            throw new IOException("Bridge manifest modpack URL must be a direct HTTPS .mrpack link.");
        }
        return packUri;
    }

    private static Path safeInstalledModPath(Path modsDirectory, String fileName) throws IOException {
        validateModFileName(fileName);
        Path normalizedDirectory = modsDirectory.toAbsolutePath().normalize();
        Path installedFile = normalizedDirectory.resolve(fileName).normalize();
        if (!installedFile.startsWith(normalizedDirectory)) {
            throw new IOException("Unsafe mod destination: " + fileName);
        }
        if (Files.isDirectory(normalizedDirectory)) {
            try (var paths = Files.list(normalizedDirectory)) {
                List<Path> matches = paths.filter(path ->
                                path.getFileName().toString().equalsIgnoreCase(fileName))
                        .toList();
                if (matches.size() > 1) {
                    throw new IOException("Multiple existing files conflict with mod filename: " + fileName);
                }
                if (!matches.isEmpty()) {
                    installedFile = matches.getFirst();
                }
            }
        }
        if (Files.isSymbolicLink(installedFile)) {
            throw new IOException("Mod destination is a symbolic link: " + fileName);
        }
        if (Files.exists(installedFile) && !Files.isRegularFile(installedFile)) {
            throw new IOException("A non-file already exists at the mod destination: " + installedFile);
        }
        return installedFile;
    }

    private static void validateModFileName(String fileName) throws IOException {
        if (fileName == null || fileName.isBlank() || fileName.contains("/")
                || fileName.contains("\\") || ".".equals(fileName) || "..".equals(fileName)
                || fileName.codePoints().anyMatch(character -> character < 32 || "<>:\"|?*".indexOf(character) >= 0)
                || !fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("The modpack contains an unsafe mod filename.");
        }
        String baseName = fileName.substring(0, fileName.length() - 4);
        if (baseName.isBlank() || baseName.endsWith(".") || baseName.endsWith(" ")
                || baseName.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")) {
            throw new IOException("The modpack contains a mod filename not supported by this operating system.");
        }
    }

    private static List<String> retainedModFiles(Path modsDirectory, List<PreviewItem> plannedItems)
            throws IOException {
        if (!Files.isDirectory(modsDirectory)) {
            return List.of();
        }
        Set<String> plannedNames = plannedItems.stream()
                .map(item -> item.fileName().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        try (var files = Files.list(modsDirectory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(name -> !plannedNames.contains(name.toLowerCase(Locale.ROOT)))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    private static PreviewAction previewRemoteFileAction(Path installedFile, ModrinthFile file)
            throws IOException {
        if (!Files.isRegularFile(installedFile)) {
            return Files.exists(installedFile) ? PreviewAction.REPLACE : PreviewAction.ADD;
        }
        if (digest(installedFile, "SHA-512").equalsIgnoreCase(file.hashes.get("sha512"))
                && digest(installedFile, "SHA-1").equalsIgnoreCase(file.hashes.get("sha1"))) {
            return PreviewAction.ALREADY_CURRENT;
        }
        return PreviewAction.REPLACE;
    }

    private static String sourceLabel(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        String fileName = path.endsWith(".mrpack") ? path.substring(path.lastIndexOf('/') + 1) : "";
        return uri.getHost() + (fileName.isBlank() ? "" : "/" + fileName);
    }

    public ServerIdentityTrustStore.Assessment inspectServerIdentity(
            SterlingConnectLink link,
            ClientProfile clientProfile,
            ServerIdentityTrustStore identityTrustStore
    ) throws Exception {
        validateClientProfile(clientProfile);
        if (link.manifestUri().getPath() != null
                && link.manifestUri().getPath().toLowerCase(Locale.ROOT).endsWith(".mrpack")) {
            throw new IOException("Direct modpack links cannot verify the server identity. "
                    + "Update Sterling Bridge and use its signed manifest link.");
        }
        SterlingManifest manifest = loadManifest(link, clientProfile);
        return identityTrustStore.assess(
                serverAddress(link), manifest.serverName, manifest.identityPublicKey);
    }

    private InstallResult downloadAndInstallMrpack(
            URI packUri,
            Path modsDirectory,
            ClientProfile clientProfile,
            String expectedPackSha256,
            ProgressListener progress,
            DownloadCancellation cancellation
    ) throws Exception {
        if (!"https".equalsIgnoreCase(packUri.getScheme())) {
            throw new IOException("Modrinth modpack links must use HTTPS.");
        }
        Path normalizedModsDirectory = modsDirectory.toAbsolutePath().normalize();
        Path gameDirectory = normalizedModsDirectory.getParent();
        if (gameDirectory == null) {
            throw new IOException("The Minecraft mods folder must have a parent directory.");
        }

        Files.createDirectories(gameDirectory);
        Path temporaryDirectory = Files.createTempDirectory(gameDirectory, ".sterling-modpack-");
        try {
            Path archive = temporaryDirectory.resolve("server.mrpack");
            downloadBounded(packUri, archive, MAX_MODPACK_BYTES, cancellation);
            cancellation.throwIfCancelled();
            if (expectedPackSha256 != null
                    && !expectedPackSha256.equalsIgnoreCase(sha256(archive, cancellation))) {
                throw new IOException("The Modrinth pack changed after the preview. Review the changes again.");
            }
            Path stagedMods = Files.createDirectory(temporaryDirectory.resolve("mods"));
            List<DownloadedMod> mods = readMrpackMods(
                    archive, stagedMods, clientProfile, progress, cancellation);
            cancellation.throwIfCancelled();
            return installMods(mods, normalizedModsDirectory, gameDirectory, cancellation);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    List<DownloadedMod> readMrpackMods(
            Path archive,
            Path stagedMods,
            ProgressListener progress
    ) throws IOException {
        return readMrpackMods(archive, stagedMods, null, progress);
    }

    List<DownloadedMod> readMrpackMods(
            Path archive,
            Path stagedMods,
            ClientProfile clientProfile,
            ProgressListener progress
    ) throws IOException {
        return readMrpackMods(archive, stagedMods, clientProfile, progress, new DownloadCancellation());
    }

    private List<DownloadedMod> readMrpackMods(
            Path archive,
            Path stagedMods,
            ClientProfile clientProfile,
            ProgressListener progress,
            DownloadCancellation cancellation
    ) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
            if (indexEntry == null || indexEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new IOException("The Modrinth pack has no valid modrinth.index.json.");
            }
            ModrinthIndex index;
            try (InputStream input = zip.getInputStream(indexEntry)) {
                index = gson.fromJson(new String(readBounded(input, MAX_MANIFEST_BYTES),
                        java.nio.charset.StandardCharsets.UTF_8), ModrinthIndex.class);
            } catch (RuntimeException exception) {
                throw new IOException("The Modrinth pack index is invalid.", exception);
            }
            if (index == null || index.formatVersion != 1 || !"minecraft".equals(index.game)
                    || index.files == null || index.files.size() > MAX_MOD_COUNT) {
                throw new IOException("The Modrinth pack is invalid or uses an unsupported format.");
            }
            if (clientProfile != null) {
                validateModrinthDependencies(index, clientProfile);
            }

            List<ModrinthFile> downloadableMods = new ArrayList<>();
            Set<String> indexedModNames = new HashSet<>();
            for (ModrinthFile file : index.files) {
                if (file == null) {
                    throw new IOException("The Modrinth pack contains an empty file entry.");
                }
                if (!isClientModPath(file.path)) {
                    continue;
                }
                validateModrinthEnvironment(file);
                String fileName = file.path.substring(MODS_DIRECTORY.length());
                ensureUniqueFileName(indexedModNames, fileName);
                if (!isClientUnsupported(file)) {
                    validateModrinthFile(file);
                    downloadableMods.add(file);
                }
            }

            Map<String, ZipEntry> bundledMods = new LinkedHashMap<>();
            Set<String> commonOverrideNames = new HashSet<>();
            Set<String> clientOverrideNames = new HashSet<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (isOverrideModEntry(entry)) {
                    String fileName = overrideFileName(entry);
                    validateModFileName(fileName);
                    String key = fileName.toLowerCase(Locale.ROOT);
                    if (entry.getName().startsWith(CLIENT_OVERRIDES_MODS_DIRECTORY)) {
                        if (!clientOverrideNames.add(key)) {
                            throw new IOException("Modrinth pack contains duplicate client override mods: " + fileName);
                        }
                        bundledMods.put(key, entry);
                    } else {
                        if (!commonOverrideNames.add(key)) {
                            throw new IOException("Modrinth pack contains duplicate override mods: " + fileName);
                        }
                        bundledMods.putIfAbsent(key, entry);
                    }
                }
            }
            if (downloadableMods.size() + bundledMods.size() > MAX_MOD_COUNT) {
                throw new IOException("The Modrinth pack contains too many client mods.");
            }
            long totalModBytes = 0;
            for (ModrinthFile file : downloadableMods) {
                totalModBytes = addModBytes(totalModBytes, file.fileSize, "Modrinth pack");
            }
            for (ZipEntry entry : bundledMods.values()) {
                if (entry.getSize() <= 0 || entry.getSize() > MAX_MOD_BYTES) {
                    throw new IOException("Bundled mod " + entry.getName() + " has an invalid size.");
                }
                totalModBytes = addModBytes(totalModBytes, entry.getSize(), "Modrinth pack");
            }

            int total = downloadableMods.size() + bundledMods.size();
            reportProgress(progress, 0, total, "");
            Map<String, DownloadedMod> mods = new LinkedHashMap<>();
            Set<String> fileNames = new HashSet<>();
            int completed = 0;
            for (ModrinthFile file : downloadableMods) {
                cancellation.throwIfCancelled();
                String fileName = file.path.substring(MODS_DIRECTORY.length());
                ensureUniqueFileName(fileNames, fileName);
                reportProgress(progress, completed, total, fileName);
                Path destination = stagedMods.resolve(completed + ".jar");
                DownloadedMod downloaded = downloadMrpackFile(file, destination, fileName, cancellation);
                mods.put(fileName.toLowerCase(Locale.ROOT), downloaded);
                reportProgress(progress, ++completed, total, fileName);
            }

            for (Map.Entry<String, ZipEntry> bundledMod : bundledMods.entrySet()) {
                cancellation.throwIfCancelled();
                ZipEntry entry = bundledMod.getValue();
                String fileName = overrideFileName(entry);
                reportProgress(progress, completed, total, fileName);
                Path destination = stagedMods.resolve(completed + ".jar");
                String hash = extractBundledMod(zip, entry, destination, cancellation);
                mods.put(bundledMod.getKey(), new DownloadedMod(fileName, destination, hash));
                reportProgress(progress, ++completed, total, fileName);
            }
            if (mods.size() > MAX_MOD_COUNT) {
                throw new IOException("The Modrinth pack contains too many client mods.");
            }
            return new ArrayList<>(mods.values());
        } catch (java.util.zip.ZipException exception) {
            throw new IOException("The Modrinth pack download is not a valid ZIP archive.", exception);
        }
    }

    private DownloadedMod downloadMrpackFile(
            ModrinthFile file,
            Path destination,
            String fileName,
            DownloadCancellation cancellation
    )
            throws IOException {
        IOException failure = null;
        for (String downloadUrl : file.downloads) {
            cancellation.throwIfCancelled();
            URI uri = URI.create(downloadUrl);
            String sha256;
            try {
                sha256 = downloadMod(uri, destination, file.fileSize, cancellation);
            } catch (RetryableDownloadException exception) {
                IOException downloadFailure = exception;
                if (failure == null) {
                    failure = downloadFailure;
                } else {
                    failure.addSuppressed(downloadFailure);
                }
                continue;
            }
            if (Files.size(destination) != file.fileSize) {
                throw new IOException("Downloaded file size does not match the Modrinth index for " + fileName);
            }
            if (!digest(destination, "SHA-512").equalsIgnoreCase(file.hashes.get("sha512"))
                    || !digest(destination, "SHA-1").equalsIgnoreCase(file.hashes.get("sha1"))) {
                throw new IOException("Modrinth hash verification failed for " + fileName);
            }
            return new DownloadedMod(fileName, destination, sha256);
        }
        throw failure == null ? new IOException("No download URL is available for " + fileName) : failure;
    }

    private static String extractBundledMod(
            ZipFile zip,
            ZipEntry entry,
            Path destination,
            DownloadCancellation cancellation
    ) throws IOException {
        if (entry.getSize() > MAX_MOD_BYTES) {
            throw new IOException("Bundled mod " + entry.getName() + " exceeds the 200 MB limit.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int totalBytes = 0;
            byte[] buffer = new byte[8192];
            try (InputStream input = new DigestInputStream(zip.getInputStream(entry), digest);
                 OutputStream output = Files.newOutputStream(destination)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    cancellation.throwIfCancelled();
                    cancellation.throwIfCancelled();
                    totalBytes += read;
                    if (totalBytes > MAX_MOD_BYTES) {
                        throw new IOException("Bundled mod " + entry.getName() + " exceeds the 200 MB limit.");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (totalBytes != entry.getSize()) {
                throw new IOException("Bundled mod " + entry.getName() + " does not match its declared size.");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static void validateModrinthFile(ModrinthFile file) throws IOException {
        if (file.hashes == null || !isSha1(file.hashes.get("sha1")) || !isSha512(file.hashes.get("sha512"))
                || file.downloads == null || file.downloads.isEmpty()
                || file.downloads.size() > MAX_MOD_DOWNLOAD_URLS
                || file.fileSize <= 0 || file.fileSize > MAX_MOD_BYTES) {
            throw new IOException("Modrinth pack contains an invalid mod entry: " + file.path);
        }
        for (String download : file.downloads) {
            URI uri;
            try {
                if (download == null || download.isBlank()) {
                    throw new IllegalArgumentException("Empty download URL");
                }
                uri = URI.create(download);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Modrinth pack contains an invalid download URL for " + file.path, exception);
            }
            validateHttpUri(uri);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Modrinth pack mod downloads must use HTTPS: " + file.path);
            }
        }
    }

    private static boolean isClientModPath(String path) throws IOException {
        if (path != null && path.toLowerCase(Locale.ROOT).startsWith("mods\\")
                && path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Modrinth pack contains an unsafe client mod path: " + path);
        }
        if (path == null || !path.toLowerCase(Locale.ROOT).startsWith(MODS_DIRECTORY)
                || !path.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        String fileName = path.substring(MODS_DIRECTORY.length());
        validateModFileName(fileName);
        return true;
    }

    private static boolean isClientUnsupported(ModrinthFile file) {
        return file.env != null && ("unsupported".equalsIgnoreCase(file.env.client)
                || "optional".equalsIgnoreCase(file.env.client));
    }

    private static void validateModrinthEnvironment(ModrinthFile file) throws IOException {
        if (file.env == null) {
            return;
        }
        if (!isValidEnvironment(file.env.client) || !isValidEnvironment(file.env.server)) {
            throw new IOException("Modrinth pack contains an invalid client/server environment for " + file.path);
        }
    }

    private static boolean isValidEnvironment(String environment) {
        return environment == null || "required".equalsIgnoreCase(environment)
                || "optional".equalsIgnoreCase(environment) || "unsupported".equalsIgnoreCase(environment);
    }

    private static boolean isTransientStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    private static boolean isRetryableIo(IOException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof SocketException
                    || cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof java.io.EOFException) {
                return true;
            }
        }
        return false;
    }

    private static void pauseBeforeRetry(int attempt, DownloadCancellation cancellation) throws IOException {
        cancellation.throwIfCancelled();
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw cancellation.cancelledException();
        }
        cancellation.throwIfCancelled();
    }

    private static boolean isOverrideModEntry(ZipEntry entry) {
        if (entry.isDirectory() || (!entry.getName().startsWith(OVERRIDES_MODS_DIRECTORY)
                && !entry.getName().startsWith(CLIENT_OVERRIDES_MODS_DIRECTORY))) {
            return false;
        }
        String fileName = overrideFileName(entry);
        return fileName.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static String overrideFileName(ZipEntry entry) {
        String directory = entry.getName().startsWith(CLIENT_OVERRIDES_MODS_DIRECTORY)
                ? CLIENT_OVERRIDES_MODS_DIRECTORY : OVERRIDES_MODS_DIRECTORY;
        return entry.getName().substring(directory.length());
    }

    private static void ensureUniqueFileName(Set<String> fileNames, String fileName) throws IOException {
        validateModFileName(fileName);
        if (!fileNames.add(fileName.toLowerCase(Locale.ROOT))) {
            throw new IOException("Modrinth pack contains duplicate mod filenames: " + fileName);
        }
    }

    private static long addModBytes(long currentTotal, long nextSize, String source) throws IOException {
        if (nextSize < 0 || nextSize > MAX_TOTAL_MOD_BYTES - currentTotal) {
            throw new IOException(source + " exceeds the 2 GB total mod size limit.");
        }
        return currentTotal + nextSize;
    }

    private static boolean isSha1(String hash) {
        return hash != null && hash.matches("(?i)[0-9a-f]{40}");
    }

    private static boolean isSha512(String hash) {
        return hash != null && hash.matches("(?i)[0-9a-f]{128}");
    }

    private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maximumBytes) {
                throw new IOException("Modrinth pack index exceeds the size limit.");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void downloadBounded(
            URI uri,
            Path destination,
            int maximumBytes,
            DownloadCancellation cancellation
    ) throws Exception {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            cancellation.throwIfCancelled();
            try {
                downloadBoundedOnce(uri, destination, maximumBytes, cancellation);
                return;
            } catch (RetryableDownloadException exception) {
                lastFailure = exception;
                Files.deleteIfExists(destination);
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    pauseBeforeRetry(attempt, cancellation);
                }
            } catch (IOException exception) {
                Files.deleteIfExists(destination);
                throw exception;
            }
        }
        throw new RetryableDownloadException(
                "Download failed after " + MAX_DOWNLOAD_ATTEMPTS + " attempts.", lastFailure);
    }

    private void downloadBoundedOnce(
            URI uri,
            Path destination,
            int maximumBytes,
            DownloadCancellation cancellation
    ) throws Exception {
        HttpResponse<InputStream> response = sendFollowingRedirects(uri, Duration.ofSeconds(45), cancellation);
        InputStream input = response.body();
        cancellation.register(input);
        try (input; OutputStream output = Files.newOutputStream(destination)) {
            if (response.statusCode() != 200) {
                if (isTransientStatus(response.statusCode())) {
                    throw new RetryableDownloadException(
                            "Modrinth pack host returned HTTP " + response.statusCode() + ".");
                }
                throw new IOException("Modrinth pack download returned HTTP " + response.statusCode());
            }
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellation.throwIfCancelled();
                totalBytes += read;
                if (totalBytes > maximumBytes) {
                    throw new IOException("Modrinth pack exceeds the 1 GB download limit.");
                }
                output.write(buffer, 0, read);
            }
        } catch (IOException exception) {
            if (cancellation.isCancelled()) {
                throw cancellation.cancelledException();
            }
            if (isRetryableIo(exception)) {
                throw new RetryableDownloadException("Temporary network failure while downloading the pack.", exception);
            }
            throw exception;
        } finally {
            cancellation.unregister(input);
        }
    }

    private SterlingManifest loadManifest(SterlingConnectLink link, ClientProfile clientProfile) throws Exception {
        return loadManifest(link, clientProfile, new DownloadCancellation());
    }

    private SterlingManifest loadManifest(
            SterlingConnectLink link,
            ClientProfile clientProfile,
            DownloadCancellation cancellation
    ) throws Exception {
        byte[] manifestJson = requestBytes(link.manifestUri(), MAX_MANIFEST_BYTES, cancellation);
        JsonObject manifestObject;
        try {
            manifestObject = JsonParser.parseString(
                    new String(manifestJson, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Bridge manifest is not valid JSON.", exception);
        }
        ManifestIdentityVerifier.VerifiedIdentity identity = ManifestIdentityVerifier.verify(manifestObject);
        SterlingManifest manifest = gson.fromJson(manifestObject, SterlingManifest.class);
        manifest.identityFingerprint = identity.fingerprint();
        validateManifest(manifest, clientProfile);
        return manifest;
    }

    private static String serverAddress(SterlingConnectLink link) {
        return link.serverIp() + ":" + link.serverPort();
    }

    private List<DownloadedMod> downloadMods(
            SterlingManifest manifest,
            Path temporaryDirectory,
            ProgressListener progress,
            DownloadCancellation cancellation
    ) throws Exception {
        List<DownloadedMod> mods = new ArrayList<>();
        Set<String> fileNames = new HashSet<>();
        List<SterlingManifest.ModEntry> requiredMods = manifest.mods.stream()
                .filter(entry -> !Boolean.FALSE.equals(entry.required))
                .toList();
        int total = requiredMods.size();
        long totalModBytes = 0;
        reportProgress(progress, 0, total, "");
        for (int index = 0; index < total; index++) {
            cancellation.throwIfCancelled();
            SterlingManifest.ModEntry entry = requiredMods.get(index);
            validateModEntry(entry, manifest.schemaVersion != null && manifest.schemaVersion == 1);
            String normalizedName = entry.fileName.toLowerCase(Locale.ROOT);
            if (!fileNames.add(normalizedName)) {
                throw new IOException("Manifestul conține nume de fișiere duplicate: " + entry.fileName);
            }
            if (entry.sizeBytes != null && totalModBytes + entry.sizeBytes > MAX_TOTAL_MOD_BYTES) {
                throw new IOException("Sterling manifest exceeds the 2 GB total mod size limit.");
            }

            Path downloadedFile = temporaryDirectory.resolve(index + ".jar");
            reportProgress(progress, index, total, entry.fileName);
            String actualHash = downloadMod(
                    URI.create(entry.downloadUrl), downloadedFile, entry.sizeBytes, cancellation);
            cancellation.throwIfCancelled();
            if (!actualHash.equalsIgnoreCase(entry.sha256)) {
                throw new IOException("SHA-256 nu corespunde pentru " + entry.fileName);
            }
            totalModBytes += Files.size(downloadedFile);
            if (totalModBytes > MAX_TOTAL_MOD_BYTES) {
                throw new IOException("Sterling manifest exceeds the 2 GB total mod size limit.");
            }
            mods.add(new DownloadedMod(entry.fileName, downloadedFile, actualHash));
            reportProgress(progress, index + 1, total, entry.fileName);
        }
        return mods;
    }

    private static void reportProgress(
            ProgressListener progress,
            int completedMods,
            int totalMods,
            String currentModName
    ) {
        progress.onProgress(new DownloadProgress(completedMods, totalMods, currentModName));
    }

    private static InstallResult installMods(
            List<DownloadedMod> mods,
            Path modsDirectory,
            Path gameDirectory,
            DownloadCancellation cancellation
    ) throws IOException {
        Files.createDirectories(modsDirectory);
        Path backupDirectory = null;
        List<InstallOperation> operations = new ArrayList<>();
        List<String> installedMods = new ArrayList<>();
        List<String> alreadyCurrentMods = new ArrayList<>();

        try {
            for (DownloadedMod mod : mods) {
                cancellation.throwIfCancelled();
                Path target = safeInstalledModPath(modsDirectory, mod.fileName);
                if (Files.isRegularFile(target) && sha256(target).equalsIgnoreCase(mod.sha256)) {
                    alreadyCurrentMods.add(mod.fileName);
                    continue;
                }

                Path backup = null;
                if (Files.exists(target)) {
                    if (backupDirectory == null) {
                        Path backupRoot = gameDirectory.resolve("sterling-mod-backups");
                        Files.createDirectories(backupRoot);
                        backupDirectory = Files.createTempDirectory(backupRoot, "mods-");
                    }
                    backup = backupDirectory.resolve(mod.fileName);
                }

                InstallOperation operation = new InstallOperation(target, backup);
                operations.add(operation);
                if (backup != null) {
                    moveFile(target, backup);
                    operation.backedUp = true;
                }
                moveFile(mod.path, target);
                operation.installed = true;
                installedMods.add(mod.fileName);
            }
            cancellation.throwIfCancelled();
        } catch (IOException exception) {
            rollbackInstall(operations, exception);
            throw exception;
        }

        return new InstallResult(installedMods, alreadyCurrentMods, backupDirectory);
    }

    private static void rollbackInstall(List<InstallOperation> operations, IOException failure) {
        for (int index = operations.size() - 1; index >= 0; index--) {
            InstallOperation operation = operations.get(index);
            try {
                if (operation.installed) {
                    Files.deleteIfExists(operation.target);
                }
                if (operation.backedUp) {
                    moveFile(operation.backup, operation.target);
                }
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private static void moveFile(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    private static String sha256(Path file, DownloadCancellation cancellation) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return digest(input, "SHA-256", Long.MAX_VALUE, cancellation);
        }
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return digest(input, algorithm, Long.MAX_VALUE);
        }
    }

    private static String digest(InputStream input, String algorithm, long maximumBytes) throws IOException {
        return digest(input, algorithm, maximumBytes, null);
    }

    private static String digest(
            InputStream input,
            String algorithm,
            long maximumBytes,
            DownloadCancellation cancellation
    ) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buffer = new byte[8192];
            long totalBytes = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (cancellation != null) {
                    cancellation.throwIfCancelled();
                }
                totalBytes += read;
                if (totalBytes > maximumBytes) {
                    throw new IOException("A bundled mod exceeds the maximum allowed file size.");
                }
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is not available.", exception);
        }
    }

    private byte[] requestBytes(URI uri, int maximumBytes) throws Exception {
        return requestBytes(uri, maximumBytes, new DownloadCancellation());
    }

    private byte[] requestBytes(
            URI uri,
            int maximumBytes,
            DownloadCancellation cancellation
    ) throws Exception {
        validateHttpUri(uri);
        HttpResponse<InputStream> response = sendFollowingRedirects(uri, Duration.ofSeconds(45), cancellation);
        InputStream input = response.body();
        cancellation.register(input);
        try (input) {
            if (response.statusCode() != 200) {
                throw new IOException("The manifest host " + uri.getHost()
                        + " returned HTTP " + response.statusCode() + ".");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maximumBytes) {
                    throw new IOException("Răspunsul de la " + uri + " depășește limita permisă.");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            if (cancellation.isCancelled()) {
                throw cancellation.cancelledException();
            }
            throw exception;
        } finally {
            cancellation.unregister(input);
        }
    }

    private String downloadMod(
            URI uri,
            Path destination,
            Long expectedSize,
            DownloadCancellation cancellation
    ) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            cancellation.throwIfCancelled();
            try {
                return downloadModOnce(uri, destination, expectedSize, cancellation);
            } catch (RetryableDownloadException exception) {
                lastFailure = exception;
                Files.deleteIfExists(destination);
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    pauseBeforeRetry(attempt, cancellation);
                }
            } catch (IOException exception) {
                Files.deleteIfExists(destination);
                throw exception;
            }
        }
        throw new RetryableDownloadException(
                "Download failed after " + MAX_DOWNLOAD_ATTEMPTS + " attempts.", lastFailure);
    }

    private String downloadModOnce(
            URI uri,
            Path destination,
            Long expectedSize,
            DownloadCancellation cancellation
    ) throws IOException {
        validateHttpUri(uri);
        HttpResponse<InputStream> response;
        try {
            response = sendFollowingRedirects(uri, Duration.ofSeconds(45), cancellation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw cancellation.cancelledException();
        }
        InputStream input = response.body();
        cancellation.register(input);
        try (input) {
            if (response.statusCode() != 200) {
                if (isTransientStatus(response.statusCode())) {
                    throw new RetryableDownloadException(
                            "Mod host returned temporary HTTP " + response.statusCode() + ".");
                }
                throw new IOException("The mod host " + uri.getHost()
                        + " returned HTTP " + response.statusCode() + ".");
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is not available.", exception);
            }
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream output = Files.newOutputStream(destination);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                int read;
                while ((read = digestInput.read(buffer)) != -1) {
                    cancellation.throwIfCancelled();
                    totalBytes += read;
                    if (totalBytes > MAX_MOD_BYTES) {
                        throw new IOException("Fișierul " + destination.getFileName() + " depășește limita de 200 MB.");
                    }
                    if (expectedSize != null && totalBytes > expectedSize) {
                        throw new IOException("Downloaded file size does not match the Sterling manifest for "
                                + destination.getFileName() + ".");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (expectedSize != null && totalBytes != expectedSize) {
                throw new IOException("Downloaded file size does not match the Sterling manifest for "
                        + destination.getFileName() + ".");
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            Files.deleteIfExists(destination);
            if (cancellation.isCancelled()) {
                throw cancellation.cancelledException();
            }
            if (isRetryableIo(exception)) {
                throw new RetryableDownloadException("Temporary network failure while downloading a mod.", exception);
            }
            throw exception;
        } finally {
            cancellation.unregister(input);
        }
    }

    private static void validateHttpUri(URI uri) throws IOException {
        if (uri == null || uri.getHost() == null || uri.getUserInfo() != null
                || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IOException("Manifestul conține un URL de descărcare invalid.");
        }
    }

    private HttpResponse<InputStream> sendFollowingRedirects(URI initialUri, Duration timeout)
            throws IOException, InterruptedException {
        return sendFollowingRedirects(initialUri, timeout, new DownloadCancellation());
    }

    private HttpResponse<InputStream> sendFollowingRedirects(
            URI initialUri,
            Duration timeout,
            DownloadCancellation cancellation
    ) throws IOException, InterruptedException {
        validateHttpUri(initialUri);
        URI currentUri = initialUri;
        boolean requireHttps = "https".equalsIgnoreCase(initialUri.getScheme());
        for (int redirects = 0; ; redirects++) {
            cancellation.throwIfCancelled();
            HttpRequest request = HttpRequest.newBuilder(currentUri)
                    .timeout(timeout)
                    .header("User-Agent", "Sterling-AutoMod/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException exception) {
                if (isRetryableIo(exception)) {
                    throw new RetryableDownloadException("Temporary network failure while connecting.", exception);
                }
                throw exception;
            }
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return response;
            }
            if (redirects >= MAX_DOWNLOAD_REDIRECTS) {
                response.body().close();
                throw new IOException("Download exceeded the maximum number of redirects.");
            }
            String location = response.headers().firstValue("Location").orElse(null);
            response.body().close();
            if (location == null || location.isBlank()) {
                throw new IOException("Download server returned a redirect without a destination.");
            }
            URI redirectUri;
            try {
                redirectUri = currentUri.resolve(location);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Download server returned an invalid redirect destination.", exception);
            }
            validateHttpUri(redirectUri);
            if (requireHttps && !"https".equalsIgnoreCase(redirectUri.getScheme())) {
                throw new IOException("HTTPS downloads cannot redirect to an insecure HTTP URL.");
            }
            currentUri = redirectUri;
        }
    }

    private static void validateManifest(SterlingManifest manifest, ClientProfile clientProfile) throws IOException {
        if (manifest == null || manifest.serverName == null || manifest.serverName.isBlank()
                || manifest.minecraftVersion == null || manifest.minecraftVersion.isBlank()
                || !"fabric".equalsIgnoreCase(manifest.loaderType)
                || manifest.loaderVersion == null || manifest.loaderVersion.isBlank()
                || manifest.mods == null || manifest.mods.size() > MAX_MOD_COUNT) {
            throw new IOException("Manifest Sterling invalid sau incompatibil cu Fabric.");
        }
        validateClientProfile(clientProfile);
        if (!manifest.minecraftVersion.equals(clientProfile.minecraftVersion())) {
            throw new IOException("This server requires Minecraft " + manifest.minecraftVersion
                    + ", but this instance is running " + clientProfile.minecraftVersion() + ".");
        }
        if (!manifest.loaderType.equalsIgnoreCase(clientProfile.loaderType())
                || !manifest.loaderVersion.equals(clientProfile.loaderVersion())) {
            throw new IOException("This server requires Fabric Loader " + manifest.loaderVersion
                    + ", but this instance is running " + clientProfile.loaderType() + " "
                    + clientProfile.loaderVersion() + ".");
        }
        if (manifest.schemaVersion != null && manifest.schemaVersion != 1) {
            throw new IOException("Bridge manifest schema version " + manifest.schemaVersion
                    + " is not supported. Update Sterling Bridge and AutoMod.");
        }
        boolean hasPackLink = manifest.modpackDownloadLink != null && !manifest.modpackDownloadLink.isBlank();
        if (manifest.schemaVersion != null) {
            String expectedInstallType = hasPackLink ? "modrinth_mrpack" : "manifest";
            if (!expectedInstallType.equals(manifest.installType)) {
                throw new IOException("Bridge manifest has an invalid install type.");
            }
        }
        boolean strictSizes = manifest.schemaVersion != null && manifest.schemaVersion == 1;
        Set<String> fileNames = new HashSet<>();
        long totalRequiredModBytes = 0;
        for (SterlingManifest.ModEntry entry : manifest.mods) {
            validateModEntry(entry, strictSizes);
            if (!fileNames.add(entry.fileName.toLowerCase(Locale.ROOT))) {
                throw new IOException("Bridge manifest contains duplicate mod filenames: " + entry.fileName);
            }
            if (!Boolean.FALSE.equals(entry.required) && entry.sizeBytes != null) {
                totalRequiredModBytes = addModBytes(
                        totalRequiredModBytes, entry.sizeBytes, "Bridge manifest");
            }
        }
    }

    private static void validateClientProfile(ClientProfile clientProfile) throws IOException {
        if (clientProfile == null || clientProfile.minecraftVersion() == null
                || clientProfile.minecraftVersion().isBlank() || clientProfile.loaderType() == null
                || clientProfile.loaderType().isBlank() || clientProfile.loaderVersion() == null
                || clientProfile.loaderVersion().isBlank()) {
            throw new IOException("Could not determine the installed Minecraft and Fabric versions.");
        }
        if (!"fabric".equalsIgnoreCase(clientProfile.loaderType())) {
            throw new IOException("Sterling AutoMod currently supports Fabric instances only.");
        }
    }

    private static void validateModEntry(SterlingManifest.ModEntry entry, boolean strictSize) throws IOException {
        if (entry == null
                || entry.sha256 == null || !entry.sha256.matches("(?i)[0-9a-f]{64}")
                || entry.downloadUrl == null || entry.downloadUrl.isBlank()
                || (strictSize && entry.sizeBytes == null)
                || (strictSize && entry.required == null)
                || (entry.sizeBytes != null && (entry.sizeBytes < 0 || entry.sizeBytes > MAX_MOD_BYTES))) {
            throw new IOException("Intrare de mod invalidă în manifest.");
        }
        validateModFileName(entry.fileName);
        URI downloadUri;
        try {
            downloadUri = URI.create(entry.downloadUrl);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Manifest contains an invalid download URL for " + entry.fileName, exception);
        }
        validateHttpUri(downloadUri);
    }

    private static void validateModrinthDependencies(ModrinthIndex index, ClientProfile clientProfile)
            throws IOException {
        if (index.dependencies == null
                || !clientProfile.minecraftVersion().equals(index.dependencies.get("minecraft"))) {
            throw new IOException("This Modrinth pack does not target Minecraft "
                    + clientProfile.minecraftVersion() + ".");
        }
        String requiredLoader = index.dependencies.get(clientProfile.loaderType().toLowerCase(Locale.ROOT) + "-loader");
        if (requiredLoader == null || !requiredLoader.equals(clientProfile.loaderVersion())) {
            throw new IOException("This Modrinth pack requires a different " + clientProfile.loaderType()
                    + " Loader version than this instance.");
        }
    }

    private static Path uniquePackPath(Path outputDirectory, String serverName) {
        String safeName = serverName.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
        if (safeName.isBlank()) {
            safeName = "Sterling Server";
        }
        String suffix = Long.toString(System.currentTimeMillis());
        return outputDirectory.resolve(safeName + "-" + suffix + ".mrpack");
    }

    private static void writePack(Path path, SterlingManifest manifest, List<DownloadedMod> mods) throws IOException {
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", "sterling-" + System.currentTimeMillis());
        index.addProperty("name", manifest.serverName);
        index.addProperty("summary", "Sterling modpack for " + manifest.serverName);
        index.add("files", new JsonArray());
        JsonObject dependencies = new JsonObject();
        dependencies.addProperty("minecraft", manifest.minecraftVersion);
        dependencies.addProperty("fabric-loader", manifest.loaderVersion);
        index.add("dependencies", dependencies);

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            addZipEntry(zip, "modrinth.index.json", index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (DownloadedMod mod : mods) {
                zip.putNextEntry(new ZipEntry("overrides/mods/" + mod.fileName));
                Files.copy(mod.path, zip);
                zip.closeEntry();
            }
        } catch (IOException exception) {
            Files.deleteIfExists(path);
            throw exception;
        }
    }

    private static void deleteTemporaryDirectory(Path temporaryDirectory) throws IOException {
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void addZipEntry(ZipOutputStream zip, String name, byte[] contents) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents);
        zip.closeEntry();
    }

    public static final class DownloadCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Set<InputStream> activeStreams = Collections.synchronizedSet(new HashSet<>());
        private volatile IOException streamCloseFailure;

        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return;
            }
            synchronized (activeStreams) {
                for (InputStream stream : activeStreams) {
                    try {
                        stream.close();
                    } catch (IOException exception) {
                        if (streamCloseFailure == null) {
                            streamCloseFailure = exception;
                        } else {
                            streamCloseFailure.addSuppressed(exception);
                        }
                    }
                }
                activeStreams.clear();
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        private void register(InputStream stream) throws IOException {
            throwIfCancelled();
            activeStreams.add(stream);
            if (cancelled.get()) {
                unregister(stream);
                stream.close();
                throw cancelledException();
            }
        }

        private void unregister(InputStream stream) {
            activeStreams.remove(stream);
        }

        private void throwIfCancelled() throws IOException {
            if (cancelled.get()) {
                throw cancelledException();
            }
        }

        private InterruptedIOException cancelledException() {
            InterruptedIOException exception =
                    new InterruptedIOException("Download cancelled. Existing mods were left unchanged.");
            if (streamCloseFailure != null) {
                exception.addSuppressed(streamCloseFailure);
            }
            return exception;
        }
    }

    private static final class RetryableDownloadException extends IOException {
        private RetryableDownloadException(String message) {
            super(message);
        }

        private RetryableDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record DownloadProgress(int completedMods, int totalMods, String currentModName) {
    }

    public record ClientProfile(String minecraftVersion, String loaderType, String loaderVersion) {
        public static ClientProfile runningClient() throws IOException {
            var loader = FabricLoader.getInstance().getModContainer("fabricloader")
                    .orElseThrow(() -> new IOException("Fabric Loader metadata is unavailable."));
            return new ClientProfile(
                    SharedConstants.getCurrentVersion().name(),
                    "fabric",
                    loader.getMetadata().getVersion().getFriendlyString());
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(DownloadProgress progress);
    }

    public record InstallResult(List<String> installedMods, List<String> alreadyCurrentMods, Path backupDirectory) {
        public InstallResult {
            installedMods = List.copyOf(installedMods);
            alreadyCurrentMods = List.copyOf(alreadyCurrentMods);
        }

        public int installedCount() {
            return installedMods.size();
        }

        public int alreadyCurrentCount() {
            return alreadyCurrentMods.size();
        }
    }

    public enum PreviewAction {
        ADD,
        REPLACE,
        ALREADY_CURRENT
    }

    public record PreviewItem(String fileName, PreviewAction action, String source) {
    }

    public record InstallPreview(
            String serverName,
            String source,
            String minecraftVersion,
            String loaderVersion,
            boolean modpack,
            String manifestSignature,
            String packSha256,
            List<PreviewItem> items,
            List<String> retainedFiles,
            List<String> skippedFiles
    ) {
        public InstallPreview {
            items = List.copyOf(items);
            retainedFiles = List.copyOf(retainedFiles);
            skippedFiles = List.copyOf(skippedFiles);
        }

        public boolean requiresPackInspection() {
            return modpack && packSha256 == null;
        }
    }

    record DownloadedMod(String fileName, Path path, String sha256) {
    }

    private static final class ModrinthIndex {
        private int formatVersion;
        private String game;
        private Map<String, String> dependencies;
        private List<ModrinthFile> files;
    }

    private static final class ModrinthFile {
        private String path;
        private java.util.Map<String, String> hashes;
        private List<String> downloads;
        private long fileSize;
        private ModrinthEnvironment env;
    }

    private static final class ModrinthEnvironment {
        private String client;
        private String server;
    }

    private static final class InstallOperation {
        private final Path target;
        private final Path backup;
        private boolean backedUp;
        private boolean installed;

        private InstallOperation(Path target, Path backup) {
            this.target = target;
            this.backup = backup;
        }
    }

    private static final class SterlingManifest {
        private Integer schemaVersion;
        private String installType;
        private String serverName;
        private String minecraftVersion;
        private String loaderType;
        private String loaderVersion;
        private String modpackDownloadLink;
        private String identityAlgorithm;
        private String identityPublicKey;
        private String manifestSignature;
        private String identityFingerprint;
        private List<ModEntry> mods;

        private static final class ModEntry {
            private String fileName;
            private String sha256;
            private String downloadUrl;
            private Long sizeBytes;
            private Boolean required;
        }
    }
}
