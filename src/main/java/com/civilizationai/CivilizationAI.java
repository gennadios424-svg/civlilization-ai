package com.civilizationai;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public final class CivilizationAI implements ModInitializer {
    public static final String MOD_ID = "civilization_ai";
    private static final String FIRST_BOT_NAME = "CivBot_001";

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(CivilizationAI::spawnFirstBot);
    }

    private static void spawnFirstBot(MinecraftServer server) {
        ServerLevel level = server.overworld();
        UUID botUuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + FIRST_BOT_NAME).getBytes(StandardCharsets.UTF_8)
        );

        if (server.getPlayerList().getPlayer(botUuid) != null) {
            return;
        }

        GameProfile profile = new GameProfile(botUuid, FIRST_BOT_NAME);
        FakePlayer bot = FakePlayer.get(level, profile);

        BlockPos spawn = level.getSharedSpawnPos();
        bot.setPos(spawn.getX() + 2.5, spawn.getY(), spawn.getZ() + 0.5);
        bot.setCustomName(Component.literal(FIRST_BOT_NAME));
        bot.setCustomNameVisible(true);

        // Add the fake player to the world and the server's player list.
        level.addNewPlayer(bot);
        server.getPlayerList().getPlayers().add(bot);

        // Tell connected clients to display the bot in the TAB player list.
        server.getPlayerList().broadcastAll(
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot))
        );

        // This is a system chat line formatted like a player message.
        // The bot does not have a real client connection or signed-chat session.
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + FIRST_BOT_NAME + "> Hello everyone! I'm the first Civilization AI."),
                false
        );

        server.getLogger().info("[Civilization AI] Spawned {}", FIRST_BOT_NAME);
    }
}
