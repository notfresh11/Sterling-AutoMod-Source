package net.sterling.automod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import net.sterling.automod.connection.AutoModConnectionState;
import net.sterling.automod.config.DiscoverConfig;
import net.sterling.automod.mixin.DisconnectedScreenAccessor;
import net.sterling.automod.screen.SterlingDownloadScreen;
import net.sterling.automod.util.SterlingConnectLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

public final class SterlingAutoModClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Sterling AutoMod");
    private static DiscoverConfig discoverConfig;

    @Override
    public void onInitializeClient() {
        Path discoverConfigPath = FabricLoader.getInstance().getConfigDir()
                .resolve("sterling")
                .resolve("automod-discover.json");
        try {
            discoverConfig = DiscoverConfig.load(discoverConfigPath);
        } catch (IOException | IllegalArgumentException exception) {
            LOGGER.error("Could not load Sterling AutoMod Discover configuration at {}.",
                    discoverConfigPath, exception);
            discoverConfig = new DiscoverConfig();
        }

        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof DisconnectedScreen)) {
                return;
            }

            String message = screen.getNarrationMessage().getString();
            var link = SterlingConnectLink.parse(message);
            if (link.isEmpty() && SterlingConnectLink.isRegistryMismatch(message)) {
                String serverAddress = AutoModConnectionState.getLastServerAddress();
                LOGGER.info("Detected Fabric registry mismatch; remembered server address available: {}",
                        serverAddress != null && !serverAddress.isBlank());
                link = SterlingConnectLink.forRegistryMismatch(
                        serverAddress, AutoModConnectionState.getLastManifestUri());
                if (link.isEmpty()) {
                    LOGGER.warn("Cannot start mod recovery for this registry mismatch because no manifest URL is known. "
                            + "Open the server through Sterling Discover or use its Sterling connect link.");
                }
            }
            link.ifPresent(connectLink -> minecraft.setScreenAndShow(
                    new SterlingDownloadScreen(
                            minecraft,
                            ((DisconnectedScreenAccessor) screen).sterling$getParent(),
                            connectLink)));
        });
    }

    public static DiscoverConfig getDiscoverConfig() {
        return discoverConfig;
    }

}
