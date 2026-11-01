package net.sterling.automod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.sterling.automod.service.ServerIdentityTrustStore;
import net.sterling.automod.service.SterlingManifestService;
import net.sterling.automod.util.SterlingConnectLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SterlingDownloadScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sterling AutoMod");
    private final Minecraft minecraft;
    private final Screen parent;
    private final SterlingConnectLink link;
    private final SterlingManifestService manifestService = new SterlingManifestService();
    private final ServerIdentityTrustStore identityTrustStore;
    private int completedMods;
    private int totalMods;
    private String currentModName = "";
    private boolean downloading;
    private boolean checkingIdentity;
    private boolean cancellableOperation;
    private SterlingManifestService.DownloadCancellation downloadCancellation;
    private ServerIdentityTrustStore.Assessment identityAssessment;
    private SterlingManifestService.InstallPreview installPreview;
    private SterlingManifestService.InstallResult installResult;
    private String status = "Verify this server's signed identity before installing any files.";
    private String error;
    private int modListScrollOffset;
    private int previewScrollOffset;

    public SterlingDownloadScreen(Minecraft minecraft, Screen parent, SterlingConnectLink link) {
        super(Component.literal("Sterling AutoMod"));
        this.minecraft = minecraft;
        this.parent = parent;
        this.link = link;
        this.identityTrustStore = new ServerIdentityTrustStore(minecraft.gameDirectory.toPath()
                .resolve("config")
                .resolve("sterling")
                .resolve("trusted-servers.json"));
    }

    @Override
    protected void init() {
        clearWidgets();
        int buttonWidth = 210;
        int left = (width - buttonWidth) / 2;
        if (installResult == null) {
            String actionLabel;
            if (identityAssessment == null) {
                actionLabel = "Check Server Identity";
            } else if (installPreview == null) {
                actionLabel = identityAssessment.status() == ServerIdentityTrustStore.TrustStatus.TRUSTED
                        ? "Review Install Plan"
                        : "Trust Identity and Review Plan";
            } else if (installPreview.requiresPackInspection()) {
                actionLabel = "Download Pack for Preview";
            } else {
                actionLabel = "Install Reviewed Changes";
            }
            Button actionButton = Button.builder(Component.literal(actionLabel), button -> {
                        if (identityAssessment == null) {
                            checkServerIdentity();
                        } else if (installPreview == null) {
                            startPreview(identityAssessment.status()
                                    != ServerIdentityTrustStore.TrustStatus.TRUSTED);
                        } else if (installPreview.requiresPackInspection()) {
                            inspectModpack();
                        } else {
                            startDownload();
                        }
                    })
                    .bounds(left, height / 2 + (error == null ? 58 : 78), buttonWidth, 20)
                    .build();
            actionButton.active = !downloading && !checkingIdentity;
            addRenderableWidget(actionButton);
            Button backButton = Button.builder(Component.literal("Back to Server List"),
                            button -> minecraft.setScreenAndShow(parent))
                    .bounds(left, height / 2 + (cancellableOperation ? 110 : 84)
                            + (error == null ? 0 : 26), buttonWidth, 20)
                    .build();
            backButton.active = !downloading && !checkingIdentity;
            addRenderableWidget(backButton);
            if (cancellableOperation) {
                Button cancelButton = Button.builder(Component.literal("Cancel Download"), button -> cancelDownload())
                        .bounds(left, height / 2 + 84, buttonWidth, 20)
                        .build();
                cancelButton.active = downloading && downloadCancellation != null
                        && !downloadCancellation.isCancelled();
                addRenderableWidget(cancelButton);
            }
        } else {
            addRenderableWidget(Button.builder(Component.literal("Exit Game"), button -> minecraft.stop())
                    .bounds(left, height - 54, buttonWidth, 20)
                    .build());
            addRenderableWidget(Button.builder(Component.literal("Back to Server List"),
                            button -> minecraft.setScreenAndShow(parent))
                    .bounds(left, height - 28, buttonWidth, 20)
                    .build());
        }
    }

    private void checkServerIdentity() {
        if (downloading || checkingIdentity) {
            return;
        }
        checkingIdentity = true;
        error = null;
        status = "Fetching and verifying the signed server identity...";
        rebuildWidgets();

        CompletableFuture.supplyAsync(() -> {
            try {
                return manifestService.inspectServerIdentity(
                        link, SterlingManifestService.ClientProfile.runningClient(), identityTrustStore);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((assessment, throwable) -> minecraft.execute(() -> {
            checkingIdentity = false;
            if (throwable != null) {
                setFailure(throwable, "Identity check failed.");
            } else {
                identityAssessment = assessment;
                status = switch (assessment.status()) {
                    case TRUSTED -> "This server's identity matches the one trusted for this address.";
                    case TRUSTED_KEY_NEW_ADDRESS ->
                            "This signing identity is already trusted from another server address.";
                    case IDENTITY_CHANGED ->
                            "WARNING: This server's signing identity has changed since it was trusted.";
                    case FIRST_USE -> "First contact: verify and explicitly trust this server identity.";
                };
            }
            rebuildWidgets();
        }));
    }

    private void startPreview(boolean approveIdentity) {
        if (downloading || checkingIdentity || identityAssessment == null) {
            return;
        }
        downloading = true;
        cancellableOperation = false;
        error = null;
        status = approveIdentity
                ? "Saving your trust decision and preparing an install preview..."
                : "Preparing an install preview...";
        rebuildWidgets();

        Path modsDirectory = minecraft.gameDirectory.toPath().resolve("mods");
        CompletableFuture.supplyAsync(() -> {
            try {
                if (approveIdentity) {
                    identityTrustStore.trust(identityAssessment);
                }
                return manifestService.createInstallPreview(link, modsDirectory,
                        SterlingManifestService.ClientProfile.runningClient(),
                        identityTrustStore);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((preview, throwable) -> minecraft.execute(() -> {
            downloading = false;
            if (throwable != null) {
                identityAssessment = null;
                setFailure(throwable, "Could not prepare the install preview.");
            } else {
                installPreview = preview;
                previewScrollOffset = 0;
                if (preview.requiresPackInspection()) {
                    status = "This server uses a modpack. Continue only to download and inspect the pack; "
                            + "nothing will be installed until you confirm the final preview.";
                } else {
                    status = "Review the planned changes below. Nothing has been downloaded or installed.";
                }
            }
            rebuildWidgets();
        }));
    }

    private void inspectModpack() {
        if (downloading || installPreview == null || !installPreview.requiresPackInspection()) {
            return;
        }
        downloading = true;
        cancellableOperation = true;
        SterlingManifestService.DownloadCancellation cancellation =
                new SterlingManifestService.DownloadCancellation();
        downloadCancellation = cancellation;
        error = null;
        status = "Downloading the pack to inspect its contents. No mods will be installed yet.";
        rebuildWidgets();
        Path modsDirectory = minecraft.gameDirectory.toPath().resolve("mods");
        CompletableFuture.supplyAsync(() -> {
            try {
                return manifestService.inspectModpackPreview(link, modsDirectory,
                        SterlingManifestService.ClientProfile.runningClient(),
                        identityTrustStore, installPreview.manifestSignature(), cancellation);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((preview, throwable) -> minecraft.execute(() -> {
            downloading = false;
            cancellableOperation = false;
            downloadCancellation = null;
            if (throwable != null) {
                if (rootCause(throwable) instanceof InterruptedIOException) {
                    status = "Pack inspection canceled. No mods were installed.";
                    error = null;
                } else {
                    setFailure(throwable, "Could not inspect the modpack.");
                }
            } else {
                installPreview = preview;
                previewScrollOffset = 0;
                status = "Review the pack contents below. Nothing has been installed.";
            }
            rebuildWidgets();
        }));
    }

    private void startDownload() {
        if (downloading || installPreview == null || installPreview.requiresPackInspection()) {
            return;
        }
        downloading = true;
        cancellableOperation = true;
        SterlingManifestService.DownloadCancellation cancellation =
                new SterlingManifestService.DownloadCancellation();
        downloadCancellation = cancellation;
        error = null;
        status = "Downloading and verifying approved files...";
        completedMods = 0;
        totalMods = 0;
        currentModName = "";
        rebuildWidgets();
        LOGGER.info("Starting approved Sterling mod download and installation.");

        Path modsDirectory = minecraft.gameDirectory.toPath().resolve("mods");
        CompletableFuture.supplyAsync(() -> {
            try {
                return manifestService.downloadAndInstallMods(link, modsDirectory,
                        SterlingManifestService.ClientProfile.runningClient(),
                        identityTrustStore,
                        installPreview.manifestSignature(),
                        installPreview.packSha256(),
                        progress -> {
                            if (!cancellation.isCancelled()) {
                                minecraft.execute(() -> updateProgress(progress));
                            }
                        },
                        cancellation);
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((result, throwable) -> minecraft.execute(() -> {
            downloading = false;
            cancellableOperation = false;
            downloadCancellation = null;
            if (throwable != null) {
                Throwable cause = rootCause(throwable);
                if (cause instanceof InterruptedIOException) {
                    status = "Download canceled. Existing mods were left unchanged.";
                    error = null;
                } else {
                    installPreview = null;
                    setFailure(throwable, "Download or installation failed.");
                }
            } else {
                installResult = result;
                modListScrollOffset = 0;
                LOGGER.info("Sterling mod installation finished: {} installed, {} already current.",
                        result.installedCount(), result.alreadyCurrentCount());
                if (result.installedCount() == 0 && result.alreadyCurrentCount() > 0) {
                    status = "All required mods are already installed. If the registry mismatch remains, "
                            + "your instance may contain extra or incompatible mods. Use a clean instance "
                            + "with Fabric, AutoMod, and this server's modpack. AutoMod will not remove mods.";
                } else if (result.installedCount() == 0) {
                    status = "No client mods were found in the server's manifest or modpack. "
                            + "If the registry mismatch remains, contact the server owner or use a clean instance.";
                } else {
                    status = "A restart of the game is required to apply changes and join the server.";
                }
                if (result.backupDirectory() != null) {
                    status += " Previous versions were backed up to: " + result.backupDirectory();
                }
            }
            rebuildWidgets();
        }));
    }

    private void cancelDownload() {
        if (downloadCancellation == null || downloadCancellation.isCancelled()) {
            return;
        }
        status = "Canceling and cleaning temporary files...";
        downloadCancellation.cancel();
        rebuildWidgets();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void setFailure(Throwable throwable, String failureStatus) {
        Throwable cause = rootCause(throwable);
        String details = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        error = userFacingError(details);
        status = failureStatus;
        LOGGER.error("Sterling AutoMod operation failed ({}): {}",
                cause.getClass().getSimpleName(), safeLogMessage(details));
    }

    private static String userFacingError(String details) {
        String requiredPrefix = "This server requires Fabric Loader ";
        String runningSeparator = ", but this instance is running ";
        if (details.startsWith(requiredPrefix)) {
            int separatorIndex = details.indexOf(runningSeparator, requiredPrefix.length());
            if (separatorIndex > requiredPrefix.length()) {
                String requiredVersion = details.substring(requiredPrefix.length(), separatorIndex);
                String installedVersion = details.substring(separatorIndex + runningSeparator.length())
                        .replaceFirst("\\.$", "");
                return "Fabric Loader version mismatch. This server requires " + requiredVersion
                        + ", but this instance uses " + installedVersion + ". Use a separate Fabric instance "
                        + "with Loader " + requiredVersion + ", restart Minecraft, and reconnect.";
            }
        }
        return details;
    }

    private void updateProgress(SterlingManifestService.DownloadProgress progress) {
        completedMods = progress.completedMods();
        totalMods = progress.totalMods();
        currentModName = progress.currentModName();
        if (totalMods == 0) {
            status = "No client mods need to be downloaded.";
        } else if (currentModName == null || currentModName.isBlank()) {
            status = "Preparing required mods...";
        } else {
            status = "Preparing " + currentModName;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (installResult != null) {
            graphics.fill(0, 0, width, height, 0x66000000);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (installResult != null) {
            int panelWidth = Math.min(520, width - 32);
            int panelLeft = (width - panelWidth) / 2;
            renderInstalledMods(graphics, panelLeft, panelWidth);
        } else if (installPreview != null) {
            renderInstallPreview(graphics);
        } else {
            graphics.centeredText(font, title, width / 2, height / 2 - 100, 0xFFFFFF);
            graphics.centeredText(font, Component.literal(link.serverIp() + ":" + link.serverPort()),
                    width / 2, height / 2 - 80, 0xAAAAAA);
            renderIdentityStatus(graphics);
        }

        if (installResult != null) {
            return;
        } else if (downloading) {
            int barWidth = Math.min(180, width - 40);
            int barLeft = (width - barWidth) / 2;
            int barTop = height / 2 - 20;
            String current = currentModName == null || currentModName.isBlank()
                    ? "Preparing mods..." : currentModName;
            String progressLabel = fitText(completedMods + "/" + totalMods + " - " + current,
                    Math.min(380, width - 40));
            graphics.centeredText(font, Component.literal(progressLabel), width / 2, barTop - 24, 0xFFFFFF);
            graphics.fill(barLeft, barTop, barLeft + barWidth, barTop + 3, 0xFF333333);
            if (totalMods > 0) {
                int filledWidth = barWidth * completedMods / totalMods;
                if (filledWidth > 0) {
                    graphics.fill(barLeft, barTop, barLeft + filledWidth, barTop + 3, 0xFF55AA55);
                }
            }
            graphics.centeredText(font, Component.literal(status), width / 2, barTop + 12, 0xFFFFFF);
        } else {
            int textWidth = Math.max(100, width - 40);
            if (identityAssessment == null) {
                graphics.textWithWordWrap(font, Component.literal(status), (width - textWidth) / 2,
                        height / 2 - 44, textWidth, 0xFFFFFF);
            }
        }

        if (installResult == null && installPreview == null && error == null) {
            renderInstanceWarning(graphics);
        }

        if (error != null) {
            renderFailure(graphics);
        }
    }

    private void renderFailure(GuiGraphicsExtractor graphics) {
        int panelWidth = Math.min(520, width - 32);
        int panelLeft = (width - panelWidth) / 2;
        int panelTop = height / 2 + 2;
        int panelBottom = panelTop + 68;
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0xDD301818);
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 2, 0xFFFF5555);
        graphics.centeredText(font, Component.literal("Action needed"), width / 2,
                panelTop + 7, 0xFFFF7777);
        graphics.textWithWordWrap(font, Component.literal(error), panelLeft + 10,
                panelTop + 22, panelWidth - 20, 0xFFFFFFFF);
    }

    private void renderInstallPreview(GuiGraphicsExtractor graphics) {
            graphics.centeredText(font, Component.literal("Review Install Preview"), width / 2, 16, 0xFFFFFFFF);
            graphics.centeredText(font,
                    Component.literal("Server: " + fitText(installPreview.serverName(), Math.max(100, width - 48))),
                    width / 2, 36, 0xFFE0E0E0);
            graphics.centeredText(font,
                    Component.literal("Source: " + fitText(installPreview.source(), Math.max(100, width - 48))),
                    width / 2, 50, 0xFFCCCCCC);
            graphics.centeredText(font,
                    Component.literal("Minecraft " + installPreview.minecraftVersion() + " / Fabric "
                            + installPreview.loaderVersion() + " | Restart required after changes"),
                    width / 2, 64, 0xFFFFCC66);
            if (installPreview.requiresPackInspection()) {
                graphics.textWithWordWrap(font,
                        Component.literal("The pack archive must be downloaded before its contents can be listed. "
                                + "This first download only inspects the archive; installation needs another confirmation."),
                        24, 92, Math.max(100, width - 48), 0xFFFFFFFF);
                return;
            }

            List<String> rows = previewRows();
            int rowHeight = 12;
            int listTop = 92;
            int listBottom = height / 2 + 42;
            int visibleRows = Math.max(1, (listBottom - listTop) / rowHeight);
            int maximumOffset = Math.max(0, rows.size() - visibleRows);
            previewScrollOffset = Math.min(previewScrollOffset, maximumOffset);
            int end = Math.min(rows.size(), previewScrollOffset + visibleRows);
            for (int index = previewScrollOffset; index < end; index++) {
                String row = fitText(rows.get(index), Math.max(100, width - 48));
                graphics.text(font, Component.literal(row), 24, listTop + (index - previewScrollOffset) * rowHeight,
                        0xFFE0E0E0);
            }
            if (rows.size() > visibleRows) {
                graphics.centeredText(font, Component.literal("Scroll to review all preview details"),
                        width / 2, listBottom - 12, 0xFFCCCCCC);
            }
            graphics.centeredText(font,
                    Component.literal("Unlisted mods are kept. Optional/unsupported entries are skipped."),
                    width / 2, height / 2 + 44, 0xFFCCCCCC);
        }

    private List<String> previewRows() {
        List<String> rows = new ArrayList<>();
        int additions = 0;
        int replacements = 0;
        int current = 0;
        for (SterlingManifestService.PreviewItem item : installPreview.items()) {
            switch (item.action()) {
                case ADD -> additions++;
                case REPLACE -> replacements++;
                case ALREADY_CURRENT -> current++;
            }
        }
            rows.add("Planned: " + additions + " add, " + replacements + " replace, "
                    + current + " already current.");
            rows.add("Files:");
            for (SterlingManifestService.PreviewItem item : installPreview.items()) {
                rows.add(item.action() + " | " + item.fileName() + " | " + item.source());
            }
            if (!installPreview.skippedFiles().isEmpty()) {
                rows.add("Skipped optional or unsupported files:");
                installPreview.skippedFiles().forEach(file -> rows.add("SKIPPED | " + file));
            }
            if (!installPreview.retainedFiles().isEmpty()) {
                rows.add("Existing mods that will be kept:");
                installPreview.retainedFiles().forEach(file -> rows.add("KEPT | " + file));
            }
            if (installPreview.items().isEmpty()) {
                rows.add("No client mods are required by this manifest or pack.");
            }
            return rows;
    }

    private void renderIdentityStatus(GuiGraphicsExtractor graphics) {
        if (identityAssessment == null) {
            return;
        }
        int textWidth = Math.max(100, width - 48);
        int textLeft = (width - textWidth) / 2;
        int textColor = identityAssessment.status() == ServerIdentityTrustStore.TrustStatus.IDENTITY_CHANGED
                ? 0xFFFF7777 : 0xFFE0E0E0;
        graphics.textWithWordWrap(font, Component.literal(status), textLeft,
                height / 2 - 66, textWidth, textColor);
        String fingerprint = "SHA-256 fingerprint: "
                + identityAssessment.fingerprint().replaceAll("(.{4})", "$1 ");
        graphics.textWithWordWrap(font, Component.literal(fingerprint), textLeft,
                height / 2 - 42, textWidth, 0xFFCCCCCC);
        if (identityAssessment.status() == ServerIdentityTrustStore.TrustStatus.IDENTITY_CHANGED) {
            String previous = "Previously trusted: "
                    + identityAssessment.previousFingerprint().replaceAll("(.{4})", "$1 ");
            graphics.textWithWordWrap(font, Component.literal(previous), textLeft,
                    height / 2 - 18, textWidth, 0xFFFF9999);
        } else if (identityAssessment.status() != ServerIdentityTrustStore.TrustStatus.TRUSTED) {
            graphics.textWithWordWrap(font,
                    Component.literal("A valid signature confirms the publisher key, not mod safety. "
                            + "Compare this fingerprint with the owner out of band."),
                    textLeft, height / 2 - 26, textWidth, 0xFFFFCC66);
        }
    }

    private void renderInstanceWarning(GuiGraphicsExtractor graphics) {
        int panelWidth = Math.min(440, width - 32);
        int panelLeft = (width - panelWidth) / 2;
        int panelTop = height / 2 + 2;
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 50, 0xCC202020);
        graphics.centeredText(font, Component.literal("Recommended: Use a separate instance per server."),
                width / 2, panelTop + 6, 0xFFFFCC66);
        graphics.textWithWordWrap(font,
                Component.literal("If you already have mods installed, use a fresh instance. "
                        + "AutoMod does not remove existing mods."),
                panelLeft + 10, panelTop + 19, panelWidth - 20, 0xFFE0E0E0);
    }

    private void renderInstalledMods(GuiGraphicsExtractor graphics, int panelLeft, int panelWidth) {
        List<String> rows = new ArrayList<>();
        if (installResult.installedMods().isEmpty() && !installResult.alreadyCurrentMods().isEmpty()) {
            rows.add("Mods already up to date (" + installResult.alreadyCurrentMods().size() + ")");
        } else {
            rows.add("Installed Mods (" + installResult.installedMods().size() + ")");
        }
        for (String mod : installResult.installedMods()) {
            rows.add("- " + mod);
        }
        if (!installResult.alreadyCurrentMods().isEmpty()) {
            rows.add("Already up to date (" + installResult.alreadyCurrentMods().size() + ")");
            for (String mod : installResult.alreadyCurrentMods()) {
                rows.add("- " + mod);
            }
        }

        graphics.centeredText(font, Component.literal("Installed Mods"), width / 2, 20, 0xFFFFFFFF);
        int rowHeight = 14;
        int listTop = 64;
        int listBottom = height - 112;
        int panelHeight = Math.max(64, listBottom - listTop);
        int visibleRows = Math.max(1, (panelHeight - 24) / rowHeight);
        int maximumOffset = Math.max(0, rows.size() - visibleRows);
        modListScrollOffset = Math.min(modListScrollOffset, maximumOffset);
        int end = Math.min(rows.size(), modListScrollOffset + visibleRows);
        for (int index = modListScrollOffset; index < end; index++) {
            String row = rows.get(index);
            int rowY = listTop + (index - modListScrollOffset) * rowHeight;
            if (index == 0 || row.startsWith("Already up to date")) {
                graphics.text(font, Component.literal(row), panelLeft + 16, rowY, 0xFFFFFF);
            } else {
                graphics.text(font, Component.literal(row), panelLeft + 28, rowY, 0xFFE0E0E0);
            }
        }
        if (rows.size() > visibleRows) {
            graphics.centeredText(font, Component.literal("Scroll to view all mods"),
                    width / 2, listBottom - 12, 0xFFCCCCCC);
        }

        int messageY = height - 88;
        if (installResult.installedMods().isEmpty() && !installResult.alreadyCurrentMods().isEmpty()) {
            graphics.centeredText(font,
                    Component.literal("If mismatch remains, use a clean instance; extra mods are not removed."),
                    width / 2, height - 112, 0xFFCCCCCC);
        } else if (installResult.installedMods().isEmpty()) {
            graphics.centeredText(font,
                    Component.literal("No client mods found. Contact the server owner if mismatch continues."),
                    width / 2, height - 112, 0xFFCCCCCC);
        }
        if (installResult.backupDirectory() != null) {
            graphics.centeredText(font, Component.literal("Previous mod versions were backed up."),
                    width / 2, height - 100, 0xFFCCCCCC);
        }
        graphics.centeredText(font, Component.literal("A restart of the game is required to apply changes"),
                width / 2, messageY, 0xFFFFFFFF);
        graphics.centeredText(font, Component.literal("and join the server."),
                width / 2, messageY + 12, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (installPreview != null && !installPreview.requiresPackInspection() && verticalAmount != 0) {
            int visibleRows = Math.max(1, (height / 2 + 42 - 92) / 12);
            int maximumOffset = Math.max(0, previewRows().size() - visibleRows);
            previewScrollOffset = Math.max(0, Math.min(maximumOffset,
                    previewScrollOffset + (verticalAmount < 0 ? 1 : -1)));
            return true;
        }
        if (installResult != null && verticalAmount != 0) {
            int panelHeight = Math.max(64, height - 112 - 64);
            int visibleRows = Math.max(1, (panelHeight - 24) / 14);
            int totalRows = 1 + installResult.installedMods().size();
            if (!installResult.alreadyCurrentMods().isEmpty()) {
                if (!installResult.installedMods().isEmpty()) {
                    totalRows++;
                }
                totalRows += installResult.alreadyCurrentMods().size();
            }
            int maximumOffset = Math.max(0, totalRows - visibleRows);
            modListScrollOffset = Math.max(0, Math.min(maximumOffset,
                    modListScrollOffset + (verticalAmount < 0 ? 1 : -1)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private String fitText(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String shortened = text;
        while (!shortened.isEmpty() && font.width(shortened + "...") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
        }
        return shortened + "...";
    }

    private static String safeLogMessage(String message) {
        return message.replaceAll("(?i)https?://[^\\s\"']+", "<download URL>");
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
