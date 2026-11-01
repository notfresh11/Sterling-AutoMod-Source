package net.sterling.automod.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.sterling.automod.screen.SterlingDiscoverScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerSelectionList.NetworkServerEntry.class)
public abstract class NetworkServerEntryMixin {
    @Shadow
    @Final
    private JoinMultiplayerScreen screen;

    @Inject(method = "extractContent", at = @At("HEAD"), cancellable = true)
    private void sterling$hideLanServer(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            boolean hovered,
            float partialTick,
            CallbackInfo callbackInfo
    ) {
        if (screen instanceof SterlingDiscoverScreen) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void sterling$ignoreHiddenLanServer(
            MouseButtonEvent event,
            boolean doubleClick,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (screen instanceof SterlingDiscoverScreen) {
            callbackInfo.setReturnValue(false);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void sterling$ignoreHiddenLanServerKeys(
            KeyEvent event,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (screen instanceof SterlingDiscoverScreen) {
            callbackInfo.setReturnValue(false);
        }
    }
}
