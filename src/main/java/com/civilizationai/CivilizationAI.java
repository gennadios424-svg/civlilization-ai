package com.civilizationai;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;

public final class CivilizationAI implements ModInitializer {
    public static final String MOD_ID = "civilization_ai";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BOTS = 100;
    private static final Random RANDOM = new Random();
    private static final String[] FIRST_NAMES = {
            "Alex", "Sam", "Noah", "Mia", "Leo", "Ava", "Kai", "Luna",
            "Eli", "Zoe", "Max", "Ivy", "Finn", "Nora", "Theo", "Ruby",
            "Owen", "Aria", "Jude", "Ezra", "Milo", "Skye", "Liam", "Eden"
    };
    private static final String[] SECOND_NAMES = {
            "Oak", "Stone", "River", "Fox", "Ash", "Pine", "Wolf", "Reed",
            "Hill", "Vale", "Brook", "Storm", "Birch", "Frost", "Meadow"
    };

    private static final Map<UUID, BotBrain> BOTS = new HashMap<>();
    private static final List<BlockPos> SHELTER_LAYOUT = createShelterLayout();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("civai")
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("count", integer(1, MAX_BOTS))
                                        .executes(context -> spawnCommand(
                                                context.getSource(),
                                                context.getArgument("count", Integer.class)
                                        ))))
                        .then(Commands.literal("list")
                                .executes(context -> listCommand(context.getSource())))
                        .then(Commands.literal("help")
                                .executes(context -> helpCommand(context.getSource())))
                )
        );

        ServerTickEvents.END_SERVER_TICK.register(CivilizationAI::tickBots);
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("[Civilization AI] Ready. Use /civai spawn <1-100> to create AI players.")
        );
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> BOTS.clear());
    }

    private static int spawnCommand(CommandSourceStack source, int count) {
        int available = MAX_BOTS - BOTS.size();
        int toSpawn = Math.min(count, available);
        if (toSpawn <= 0) {
            source.sendFailure(Component.literal("The civilization already has the maximum of " + MAX_BOTS + " AI players."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        BlockPos origin = BlockPos.containing(source.getPosition());
        int spawned = 0;
        for (int i = 0; i < toSpawn; i++) {
            String name = randomName();
            FakePlayer bot = createBot(level, origin, name);
            if (bot != null) spawned++;
        }

        final int spawnedCount = spawned;
        source.sendSuccess(() -> Component.literal("Spawned " + spawnedCount + " AI player(s). Total: " + BOTS.size() + "/" + MAX_BOTS + "."), true);
        return spawned;
    }

    private static int listCommand(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Civilization AI players: " + BOTS.size() + "/" + MAX_BOTS), false);
        int shown = 0;
        for (BotBrain brain : BOTS.values()) {
            if (shown++ >= 20) {
                source.sendSuccess(() -> Component.literal("...and " + (BOTS.size() - 20) + " more."), false);
                break;
            }
            source.sendSuccess(() -> Component.literal("- " + brain.player.getGameProfile().getName() + " (" + brain.state + ")"), false);
        }
        return BOTS.size();
    }

    private static int helpCommand(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Civilization AI commands: /civai spawn <1-100>, /civai list, /civai help"
        ), false);
        return 1;
    }

    private static FakePlayer createBot(ServerLevel level, BlockPos origin, String name) {
        UUID uuid = UUID.randomUUID();
        GameProfile profile = new GameProfile(uuid, name);
        FakePlayer bot = FakePlayer.get(level, profile);

        BlockPos spawn = findSafeSpawn(level, origin);
        bot.setPos(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        bot.setYRot(RANDOM.nextFloat() * 360.0F);
        bot.setCustomName(Component.literal(name));
        bot.setCustomNameVisible(true);

        // Register the fake player with the world entity tracker and publish its profile first.
        level.addNewPlayer(bot);
        level.getServer().getPlayerList().broadcastAll(
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot))
        );
        // Explicitly send the player spawn packet so clients create the visible player model.
        ServerEntity tracker = new ServerEntity(level, bot, 1, true, packet ->
                level.getServer().getPlayerList().broadcastAll(packet)
        );
        level.getServer().getPlayerList().broadcastAll(new ClientboundAddEntityPacket(bot, tracker));
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("<" + name + "> Hey! I'm exploring this world."),
                false
        );

        BOTS.put(uuid, new BotBrain(bot, level));
        LOGGER.info("[Civilization AI] Spawned {}", name);
        return bot;
    }

    private static BlockPos findSafeSpawn(ServerLevel level, BlockPos origin) {
        for (int radius = 0; radius <= 8; radius += 2) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = origin.offset(dx, 0, dz);
                    BlockPos surface = level.getHeightmapPos(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            candidate
                    );
                    if (level.getBlockState(surface).isAir()
                            && level.getBlockState(surface.above()).isAir()
                            && level.getBlockState(surface.below()).isSolid()) {
                        return surface;
                    }
                }
            }
        }
        return level.getSharedSpawnPos();
    }

    private static String randomName() {
        String first = FIRST_NAMES[RANDOM.nextInt(FIRST_NAMES.length)];
        String second = SECOND_NAMES[RANDOM.nextInt(SECOND_NAMES.length)];
        String suffix = Integer.toString(10 + RANDOM.nextInt(90));
        String name = first + second + suffix;
        return name.length() > 16 ? first + suffix : name;
    }

    private static void tickBots(MinecraftServer server) {
        for (BotBrain brain : BOTS.values()) {
            if (brain.player.isRemoved() || !brain.level.players().contains(brain.player)) continue;
            brain.tick(server.getTickCount());
        }
        if (server.getTickCount() % 10 == 0) coordinateTeams();
    }

    private static void coordinateTeams() {
        List<BotBrain> active = new ArrayList<>(BOTS.values());
        for (int i = 0; i < active.size(); i++) {
            BotBrain leader = active.get(i);
            if (leader.player.isRemoved()) continue;
            for (int j = i + 1; j < active.size(); j++) {
                BotBrain member = active.get(j);
                if (member.player.isRemoved() || member.level != leader.level) continue;
                if (leader.player.distanceToSqr(member.player) > 36.0) continue;

                Vec3 towardMember = member.player.getEyePosition().subtract(leader.player.getEyePosition());
                float leaderYaw = (float) (Math.atan2(-towardMember.x, towardMember.z) * (180.0 / Math.PI));
                leader.player.setYRot(leaderYaw);
                leader.player.setYHeadRot(leaderYaw);
                leader.player.yBodyRot = leaderYaw;
                leader.player.setShiftKeyDown(true);
                member.player.setShiftKeyDown(true);
                leader.crouchTicks = Math.max(leader.crouchTicks, 20);
                member.crouchTicks = Math.max(member.crouchTicks, 20);

                // Share spare building blocks so the pair can contribute to one shelter.
                for (int slot = 0; slot < member.player.getInventory().getContainerSize(); slot++) {
                    ItemStack stack = member.player.getInventory().getItem(slot);
                    if (stack.isEmpty() || Block.byItem(stack.getItem()) == Blocks.AIR) continue;
                    ItemStack transfer = stack.copy();
                    transfer.setCount(Math.min(8, stack.getCount()));
                    if (leader.player.getInventory().add(transfer)) stack.shrink(transfer.getCount());
                    break;
                }
            }
        }
    }

    private static List<BlockPos> createShelterLayout() {
        List<BlockPos> positions = new ArrayList<>();
        // A compact 3x3 shelter: low walls with a doorway, plus a flat roof.
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                if (Math.abs(x) == 1 || Math.abs(z) == 1) {
                    if (!(x == 0 && z == -1)) positions.add(new BlockPos(x, 0, z));
                }
            }
        }
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                positions.add(new BlockPos(x, 1, z));
            }
        }
        return positions;
    }

    private static final class BotBrain {
        private final FakePlayer player;
        private final ServerLevel level;
        private BlockPos target;
        private int actionCooldown;
        private int scanCooldown;
        private int shelterIndex;
        private BlockPos shelterOrigin;
        private State state = State.SEARCHING;
        private int crouchTicks;
        private int socialCooldown;

        private BotBrain(FakePlayer player, ServerLevel level) {
            this.player = player;
            this.level = level;
        }

        private void tick(int gameTick) {
            if (actionCooldown > 0) actionCooldown--;
            if (scanCooldown > 0) scanCooldown--;
            if (socialCooldown > 0) socialCooldown--;
            if (crouchTicks > 0) { crouchTicks--; player.setShiftKeyDown(true); } else player.setShiftKeyDown(false);
            handleNearbyPlayers();

            if (state == State.BUILDING) {
                buildShelter();
                return;
            }

            if (target == null || scanCooldown == 0) {
                if (target == null || player.blockPosition().distSqr(target) < 4 || scanCooldown == 0) {
                    target = findNearestTreeLog(50);
                    if (target == null) {
                        target = findNearestEasyBlock(16);
                        state = State.GATHERING;
                    } else {
                        state = State.SEARCHING;
                    }
                    scanCooldown = 100;
                }
            }

            if (target != null) {
                if (player.distanceToSqr(Vec3.atCenterOf(target)) <= 2.25) {
                    if (actionCooldown == 0) {
                        gatherTarget();
                        actionCooldown = 12;
                        target = null;
                    }
                } else {
                    walkToward(target);
                }
            } else {
                lookAround(gameTick);
            }

            if (countGatheredBlocks() >= 12) {
                state = State.BUILDING;
                shelterOrigin = player.blockPosition().offset(3, 0, 0);
                shelterIndex = 0;
                target = null;
            }
        }

        private BlockPos findNearestTreeLog(int radius) {
            BlockPos center = player.blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = -radius; x <= radius; x += 2) {
                for (int z = -radius; z <= radius; z += 2) {
                    if (x * x + z * z > radius * radius) continue;
                    for (int y = -4; y <= 16; y++) {
                        BlockPos pos = center.offset(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        if (level.getBlockState(pos).is(BlockTags.LOGS)) {
                            double distance = pos.distSqr(center);
                            if (distance < bestDistance) {
                                best = pos.immutable();
                                bestDistance = distance;
                            }
                        }
                    }
                }
            }
            return best;
        }

        private BlockPos findNearestEasyBlock(int radius) {
            BlockPos center = player.blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -2; y <= 3; y++) {
                        BlockPos pos = center.offset(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        BlockState state = level.getBlockState(pos);
                        boolean easy = state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                                || state.is(Blocks.SAND) || state.is(Blocks.GRAVEL)
                                || state.is(BlockTags.LEAVES);
                        if (easy && state.getDestroySpeed(level, pos) >= 0) {
                            double distance = pos.distSqr(center);
                            if (distance < bestDistance) {
                                best = pos.immutable();
                                bestDistance = distance;
                            }
                        }
                    }
                }
            }
            return best;
        }

        private void walkToward(BlockPos destination) {
            Vec3 current = player.position();
            Vec3 targetPoint = Vec3.atBottomCenterOf(destination);
            Vec3 direction = new Vec3(targetPoint.x - current.x, 0, targetPoint.z - current.z);
            double distance = direction.length();
            if (distance < 0.05) return;
            // Small per-tick steps avoid visible position jumps from coarse 5-tick updates.
            Vec3 step = direction.normalize().scale(Math.min(0.055, distance));
            float desiredYaw = (float) (Math.atan2(-step.x, step.z) * (180.0 / Math.PI));
            float yawDelta = net.minecraft.util.Mth.wrapDegrees(desiredYaw - player.getYRot());
            player.setYRot(player.getYRot() + net.minecraft.util.Mth.clamp(yawDelta, -2.5F, 2.5F));
            player.yBodyRot = player.getYRot();
            Vec3 velocity = player.getDeltaMovement();
            if (!level.noCollision(player, player.getBoundingBox().move(step.x * 2.0, 0, step.z * 2.0))) {
                if (player.onGround()) player.jumpFromGround();
                else step = Vec3.ZERO;
            }
            // Vanilla ServerPlayer ticking applies gravity, collision response and limb animation.
            player.setDeltaMovement(step.x * 0.65, velocity.y, step.z * 0.65);
        }

        private void gatherTarget() {
            if (target == null) return;
            BlockState state = level.getBlockState(target);
            if (state.isAir() || state.getDestroySpeed(level, target) < 0) return;
            Block itemBlock = state.getBlock();
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (level.destroyBlock(target, true, player)) {
                ItemStack collected = new ItemStack(itemBlock.asItem());
                if (!collected.isEmpty()) player.getInventory().add(collected);
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal("<" + player.getGameProfile().getName() + "> I found some " + itemBlock.getName().getString().toLowerCase() + "."),
                        false
                );
            }
        }

        private int countGatheredBlocks() {
            int total = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && Block.byItem(stack.getItem()) != Blocks.AIR) total += stack.getCount();
            }
            return total;
        }

        private void buildShelter() {
            if (shelterOrigin == null) shelterOrigin = player.blockPosition().offset(3, 0, 0);
            if (shelterIndex >= SHELTER_LAYOUT.size()) {
                state = State.EXPLORING;
                shelterOrigin = null;
                target = null;
                player.getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal("<" + player.getGameProfile().getName() + "> Shelter built! This place is ours. Time to explore."),
                        false
                );
                return;
            }

            BlockPos placeAt = shelterOrigin.offset(
                    SHELTER_LAYOUT.get(shelterIndex).getX(),
                    SHELTER_LAYOUT.get(shelterIndex).getY(),
                    SHELTER_LAYOUT.get(shelterIndex).getZ()
            );
            shelterIndex++;
            if (!level.getBlockState(placeAt).isAir()) return;

            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                Block block = Block.byItem(stack.getItem());
                if (block == Blocks.AIR) continue;
                if (player.distanceToSqr(Vec3.atCenterOf(placeAt)) > 20.25) {
                    walkToward(placeAt);
                    shelterIndex--;
                    return;
                }
                player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                level.setBlock(placeAt, block.defaultBlockState(), 3);
                stack.shrink(1);
                return;
            }
            state = State.GATHERING;
            target = findNearestEasyBlock(16);
        }

        private void handleNearbyPlayers() {
            if (socialCooldown > 0) return;
            for (ServerPlayer nearby : level.players()) {
                if (nearby == player || nearby.isRemoved() || nearby.distanceToSqr(player) > 25.0) continue;
                Vec3 delta = nearby.getEyePosition().subtract(player.getEyePosition());
                double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
                if (horizontal > 0.001) {
                    float yaw = (float) (Math.atan2(-delta.x, delta.z) * (180.0 / Math.PI));
                    float pitch = (float) (-Math.atan2(delta.y, horizontal) * (180.0 / Math.PI));
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                    player.setYHeadRot(yaw);
                }
                crouchTicks = 24;
                player.setShiftKeyDown(true);
                if (nearby.isShiftKeyDown()) {
                    level.getServer().getPlayerList().broadcastSystemMessage(
                            Component.literal(player.getGameProfile().getName() + " and " + nearby.getGameProfile().getName() + " are teaming up!"),
                            false
                    );
                }
                socialCooldown = 100;
                break;
            }
        }

        private void lookAround(int gameTick) {
            if (gameTick % 40 == 0) {
                player.setYRot(player.getYRot() + 35.0F);
            }
            if (state == State.EXPLORING && gameTick % 200 == 0) {
                int dx = RANDOM.nextInt(41) - 20;
                int dz = RANDOM.nextInt(41) - 20;
                target = player.blockPosition().offset(dx, 0, dz);
                state = State.SEARCHING;
            }
        }
    }

    private enum State {
        SEARCHING, GATHERING, BUILDING, EXPLORING
    }
}
