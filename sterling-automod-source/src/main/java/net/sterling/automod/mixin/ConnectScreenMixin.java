package net.sterling.automod.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.sterling.automod.connection.AutoModConnectionState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectScreen.class)
public abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void sterling$rememberServerAddress(
            Screen parent,
            Minecraft minecraft,
            ServerAddress address,
            ServerData serverData,
            boolean quickPlay,
            TransferState transferState,
            CallbackInfo callbackInfo
    ) {
        if (serverData != null) {
            AutoModConnectionState.rememberServerAddress(serverData.ip);
        } else if (address != null) {
            AutoModConnectionState.rememberServerAddress(address.getHost() + ":" + address.getPort());
        }
    }
}
