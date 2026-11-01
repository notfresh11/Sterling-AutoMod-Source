package net.sterling.automod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import net.sterling.automod.SterlingAutoModClient;
import net.sterling.automod.screen.SterlingDiscoverScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin() {
        super(Component.empty());
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void sterling$removePreviouslyPersistedDiscoverServers(CallbackInfo callbackInfo) {
        if ((Object) this instanceof SterlingDiscoverScreen) {
            return;
        }
        ServerList servers = ((JoinMultiplayerScreen) (Object) this).getServers();
        boolean removedLegacyEntry = false;
        for (int index = servers.size() - 1; index >= 0; index--) {
            ServerData server = servers.get(index);
            if (server.name.startsWith("✓ ") || server.name.startsWith("✕ ")) {
                servers.remove(server);
                removedLegacyEntry = true;
            }
        }
        if (removedLegacyEntry) {
            servers.save();
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void sterling$addDiscoverButton(CallbackInfo callbackInfo) {
        if ((Object) this instanceof SterlingDiscoverScreen) {
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("Discover Servers"),
                        button -> Minecraft.getInstance().setScreenAndShow(
                                new SterlingDiscoverScreen(
                                        Minecraft.getInstance(),
                                        (Screen) (Object) this,
                                        SterlingAutoModClient.getDiscoverConfig())))
                .bounds(0, height - 28, 150, 20)
                .build());
        sterling$arrangeButtons();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void sterling$arrangeMultiplayerButtons(CallbackInfo callbackInfo) {
        sterling$arrangeButtons();
    }

    private void sterling$arrangeButtons() {
        if ((Object) this instanceof SterlingDiscoverScreen) {
            return;
        }
        List<Button> buttons = children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> List.of(
                        "Join Server", "Direct Connection", "Add Server", "Edit", "Delete", "Refresh", "Back")
                        .contains(button.getMessage().getString()))
                .toList();
        Button discoverButton = children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(button -> "Discover Servers".equals(button.getMessage().getString()))
                .findFirst()
                .orElse(null);
        if (discoverButton == null) {
            return;
        }
        buttons = new java.util.ArrayList<>(buttons);
        buttons.add(discoverButton);

        Button joinButton = findButton(buttons, "Join Server");
        Button backButton = findButton(buttons, "Back");
        int firstRowY = joinButton == null ? height - 78 : joinButton.getY();
        int secondRowY = backButton == null ? height - 42 : backButton.getY();
        int gap = 4;
        int[] columnWidths = {112, 136, 136, 112};
        int nominalWidth = java.util.Arrays.stream(columnWidths).sum();
        int availableWidth = Math.max(columnWidths.length, width - 32 - gap * 3);
        if (nominalWidth > availableWidth) {
            for (int index = 0; index < columnWidths.length; index++) {
                columnWidths[index] = Math.max(1, columnWidths[index] * availableWidth / nominalWidth);
            }
        }
        int rowWidth = java.util.Arrays.stream(columnWidths).sum() + gap * 3;
        int startX = (width - rowWidth) / 2;
        arrangeRow(buttons, firstRowY, startX, gap, columnWidths,
                "Join Server", "Direct Connection", "Discover Servers", "Add Server");
        arrangeRow(buttons, secondRowY, startX, gap, columnWidths, "Edit", "Delete", "Refresh", "Back");
    }

    @Inject(method = "refreshServerList", at = @At("HEAD"), cancellable = true)
    private void sterling$refreshDiscoverCatalog(CallbackInfo callbackInfo) {
        if ((Object) this instanceof SterlingDiscoverScreen discoverScreen) {
            discoverScreen.refreshCatalog();
            callbackInfo.cancel();
        }
    }

    private static Button findButton(List<Button> buttons, String label) {
        return buttons.stream()
                .filter(button -> label.equals(button.getMessage().getString()))
                .findFirst()
                .orElse(null);
    }

    private void arrangeRow(
            List<Button> buttons,
            int y,
            int startX,
            int gap,
            int[] columnWidths,
            String first,
            String second,
            String third,
            String fourth
    ) {
        List<String> labels = List.of(first, second, third, fourth);
        int x = startX;
        for (int index = 0; index < labels.size(); index++) {
            String label = labels.get(index);
            Button button = findButton(buttons, label);
            if (button == null) {
                continue;
            }
            button.setWidth(columnWidths[index]);
            button.setX(x);
            button.setY(y);
            x += columnWidths[index] + gap;
        }
    }
}
