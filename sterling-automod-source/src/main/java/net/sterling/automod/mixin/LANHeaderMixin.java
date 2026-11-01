package net.sterling.automod.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.sterling.automod.screen.SterlingDiscoverScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerSelectionList.LANHeader.class)
public abstract class LANHeaderMixin {
    @Inject(method = "extractContent", at = @At("HEAD"), cancellable = true)
    private void sterling$hideLanScanning(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            boolean hovered,
            float partialTick,
            CallbackInfo callbackInfo
    ) {
        if (SterlingDiscoverScreen.isActive()) {
            callbackInfo.cancel();
        }
    }
}
