package net.sterling.automod.mixin;

import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(JoinMultiplayerScreen.class)
public interface JoinMultiplayerScreenAccessor {
    @Accessor("servers")
    void sterling$setServers(ServerList servers);
}
