package net.sterling.automod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.sterling.automod.connection.AutoModConnectionState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ServerSelectionList.OnlineServerEntry.class)
public abstract class OnlineServerEntryMixin {
    @Shadow
    public abstract ServerData getServerData();

    @Inject(method = "extractContent", at = @At("TAIL"))
    private void sterling$drawDiscoveryVerification(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            boolean hovered,
            float partialTick,
            CallbackInfo callbackInfo
    ) {
        Boolean verified = AutoModConnectionState.getDiscoveryVerification(getServerData().ip);
        if (verified == null) {
            return;
        }
        ObjectSelectionList.Entry<?> entry =
                (ObjectSelectionList.Entry<?>) (Object) this;
        String marker = verified ? "✓" : "✕";
        int markerWidth = Minecraft.getInstance().font.width(marker);
        int markerX = entry.getContentRight() - 6;
        int markerY = entry.getContentY() + 1;
        graphics.text(Minecraft.getInstance().font, marker,
                markerX, markerY, verified ? 0xFF55FF55 : 0xFFFF5555);
        if (mouseX >= markerX - 1 && mouseX <= markerX + markerWidth + 1
                && mouseY >= markerY - 1 && mouseY <= markerY + 10) {
            List<Component> tooltip = verified
                    ? List.of(
                            Component.literal("Verified server"),
                            Component.literal("The owner proved control of the listed domain."))
                    : List.of(
                            Component.literal("Unverified server"),
                            Component.literal("The owner has not proved control of the listed address."));
            graphics.setComponentTooltipForNextFrame(
                    Minecraft.getInstance().font, tooltip, mouseX, mouseY);
        }
    }
}
