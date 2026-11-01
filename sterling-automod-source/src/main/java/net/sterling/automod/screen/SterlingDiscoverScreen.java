package net.sterling.automod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.sterling.automod.config.DiscoverConfig;
import net.sterling.automod.connection.AutoModConnectionState;
import net.sterling.automod.mixin.JoinMultiplayerScreenAccessor;
import net.sterling.automod.service.SterlingDiscoverService;
import net.sterling.automod.service.SterlingDiscoverService.ServerListing;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SterlingDiscoverScreen extends JoinMultiplayerScreen {
    private static boolean active;

    private final Minecraft minecraft;
    private final DiscoverConfig config;
    private final ServerList discoverServers;
    private final SterlingDiscoverService discoverService = new SterlingDiscoverService();
    private boolean loading = true;
    private int requestGeneration;
    private String error;

    public SterlingDiscoverScreen(Minecraft minecraft, Screen parent, DiscoverConfig config) {
        super(parent);
        this.minecraft = minecraft;
        this.config = config;
        this.discoverServers = new DiscoverServerList(minecraft);
    }

    @Override
    protected void init() {
        active = true;
        super.init();
        ((JoinMultiplayerScreenAccessor) (Object) this).sterling$setServers(discoverServers);
        serverSelectionList.updateOnlineServers(discoverServers);
        removeUnusedButtons();
        arrangeActionButtons();
        if (requestGeneration == 0) {
            fetchServers();
        }
    }

    private void fetchServers() {
        if (config.getCatalogUrl().isBlank()) {
            loading = false;
            error = "Sterling Discover catalog URL is not configured.";
            return;
        }
        loading = true;
        error = null;
        int generation = ++requestGeneration;
        CompletableFuture.supplyAsync(() -> {
            try {
                return discoverService.fetchServers(config.getCatalogUrl());
            } catch (Exception exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((result, throwable) -> minecraft.execute(() -> {
            if (generation != requestGeneration) {
                return;
            }
            loading = false;
            if (throwable != null) {
                Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
                error = cause.getMessage() == null ? "Could not load Sterling servers." : cause.getMessage();
                return;
            }
            replaceServerEntries(result);
        }));
    }

    private void replaceServerEntries(List<ServerListing> listings) {
        clearServerEntries();
        AutoModConnectionState.clearDiscoveredManifests();
        for (ServerListing listing : listings) {
            boolean verified = !"unverified".equals(listing.verificationStatus());
            String address = listing.address();
            if (listing.port() != 25565) {
                address += ":" + listing.port();
            }
            AutoModConnectionState.rememberDiscoveryVerification(address, verified);
            if (listing.manifestUrl() != null) {
                AutoModConnectionState.rememberDiscoveredManifest(address, listing.manifestUrl());
            }
            getServers().add(
                    new ServerData(listing.name(), address, ServerData.Type.OTHER),
                    false);
        }
        serverSelectionList.updateOnlineServers(getServers());
    }

    private void removeUnusedButtons() {
        for (GuiEventListener child : List.copyOf(children())) {
            if (child instanceof Button button && List.of(
                    "Direct Connection", "Add Server", "Edit", "Delete")
                    .contains(button.getMessage().getString())) {
                removeWidget(button);
            }
        }
    }

    public void refreshCatalog() {
        if (!loading) {
            fetchServers();
        }
    }

    public static boolean isActive() {
        return active;
    }

    private void arrangeActionButtons() {
        List<Button> actionButtons = children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> List.of("Join Server", "Refresh", "Back")
                        .contains(button.getMessage().getString()))
                .sorted((first, second) -> Integer.compare(
                        actionOrder(first.getMessage().getString()),
                        actionOrder(second.getMessage().getString())))
                .toList();
        if (actionButtons.isEmpty()) {
            return;
        }

        int gap = 8;
        int buttonWidth = Math.max(1, Math.min(210, (width - 64 - gap * (actionButtons.size() - 1))
                / actionButtons.size()));
        int startX = (width - (buttonWidth * actionButtons.size() + gap * (actionButtons.size() - 1))) / 2;
        int rowY = actionButtons.stream()
                .filter(button -> "Back".equals(button.getMessage().getString()))
                .mapToInt(Button::getY)
                .findFirst()
                .orElse(height - 28);
        for (int index = 0; index < actionButtons.size(); index++) {
            Button button = actionButtons.get(index);
            button.setWidth(buttonWidth);
            button.setX(startX + index * (buttonWidth + gap));
            button.setY(rowY);
        }
    }

    private static int actionOrder(String label) {
        return switch (label) {
            case "Join Server" -> 0;
            case "Refresh" -> 1;
            case "Back" -> 2;
            default -> 3;
        };
    }

    private void clearServerEntries() {
        while (getServers().size() > 0) {
            getServers().remove(getServers().get(0));
        }
        serverSelectionList.updateOnlineServers(getServers());
    }

    @Override
    public void removed() {
        requestGeneration++;
        active = false;
        AutoModConnectionState.clearDiscoveryVerification();
        super.removed();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        arrangeActionButtons();
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (loading) {
            graphics.centeredText(font, net.minecraft.network.chat.Component.literal("Loading Sterling servers..."),
                    width / 2, height / 2, 0xFFCCCCCC);
        } else if (error != null) {
            graphics.textWithWordWrap(font, net.minecraft.network.chat.Component.literal(error),
                    20, height / 2, width - 40, 0xFFFF7777);
        } else if (getServers().size() == 0) {
            graphics.centeredText(font,
                    net.minecraft.network.chat.Component.literal("No active Sterling Bridge servers were found."),
                    width / 2, height / 2, 0xFFCCCCCC);
        }
    }

    private static final class DiscoverServerList extends ServerList {
        private DiscoverServerList(Minecraft minecraft) {
            super(minecraft);
        }

        @Override
        public void save() {
            // Discover entries are temporary and must not be saved to Multiplayer.
        }
    }
}
