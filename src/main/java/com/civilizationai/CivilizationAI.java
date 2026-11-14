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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
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
    private static final Map<UUID, Settlement> SETTLEMENTS = new HashMap<>();
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
                        .then(Commands.literal("settlements")
                                .executes(context -> settlementsCommand(context.getSource())))
                        .then(Commands.literal("help")
                                .executes(context -> helpCommand(context.getSource())))
                )
        );

        ServerTickEvents.END_SERVER_TICK.register(CivilizationAI::tickBots);
        ServerLifecycleEvents.SERVER_STARTED.register(server ->
                LOGGER.info("[Civilization AI] Ready. Use /civai spawn <1-100> to create AI players.")
        );
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            BOTS.clear();
            SETTLEMENTS.clear();
        });
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
            source.sendSuccess(() -> Component.literal(
                    "- " + brain.player.getGameProfile().name()
                            + " | " + brain.profession
                            + " | " + brain.state
                            + (brain.settlement == null ? " | Unsettled" : " | " + brain.settlement.name())
            ), false);
        }
        return BOTS.size();
    }

    private static int settlementsCommand(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Civilization settlements: " + SETTLEMENTS.size()), false);
        for (Settlement settlement : SETTLEMENTS.values()) {
            if (settlement.level() != source.getLevel()) continue;
            source.sendSuccess(() -> Component.literal(
                    settlement.name() + " | Citizens: " + settlement.members().size()
                            + " | Center: " + settlement.center().toShortString()
                            + " | Shared planks: " + settlement.stock("planks")
            ), false);
        }
        return SETTLEMENTS.size();
    }

    private static int helpCommand(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "Civilization AI commands: /civai spawn <1-100>, /civai list, /civai settlements, /civai help"
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
        BotBrain brain = new BotBrain(bot, level);
        BOTS.put(uuid, brain);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("<" + name + "> " + brain.personality.greeting()),
                false
        );
        assignNearestSettlement(brain);
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
        return BlockPos.ZERO;
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
            BotBrain first = active.get(i);
            if (first.player.isRemoved()) continue;
            for (int j = i + 1; j < active.size(); j++) {
                BotBrain second = active.get(j);
                if (second.player.isRemoved() || second.level != first.level) continue;
                double distance = first.player.distanceToSqr(second.player);
                if (distance > 36.0) continue;

                UUID firstId = second.player.getUUID();
                UUID secondId = first.player.getUUID();
                int bondGain = Math.max(1, Math.round(
                        first.personality.sociability() + second.personality.sociability()
                                + first.personality.cooperation() * 0.5F
                ));
                int bond = Math.min(100, first.relationships.getOrDefault(firstId, 0) + bondGain);
                first.relationships.put(firstId, bond);
                second.relationships.put(secondId, bond);
                if (first.settlement != null && second.settlement == null) {
                    first.settlement.addMember(second.player.getUUID(), second.profession);
                    second.settlement = first.settlement;
                } else if (second.settlement != null && first.settlement == null) {
                    second.settlement.addMember(first.player.getUUID(), first.profession);
                    first.settlement = second.settlement;
                }

                // Friendship gestures are occasional and only happen when neither bot is busy.
                if (bond >= 40 && first.personality.sociability() > 0.55F
                        && second.personality.sociability() > 0.55F
                        && first.friendshipGestureCooldown == 0
                        && second.friendshipGestureCooldown == 0
                        && first.target == null && second.target == null
                        && first.state != State.BUILDING && second.state != State.BUILDING) {
                    Vec3 towardSecond = second.player.getEyePosition().subtract(first.player.getEyePosition());
                    float firstYaw = (float) (Math.atan2(-towardSecond.x, towardSecond.z) * (180.0 / Math.PI));
                    first.player.setYRot(firstYaw);
                    first.player.setYHeadRot(firstYaw);
                    first.player.yBodyRot = firstYaw;

                    Vec3 towardFirst = first.player.getEyePosition().subtract(second.player.getEyePosition());
                    float secondYaw = (float) (Math.atan2(-towardFirst.x, towardFirst.z) * (180.0 / Math.PI));
                    second.player.setYRot(secondYaw);
                    second.player.setYHeadRot(secondYaw);
                    second.player.yBodyRot = secondYaw;

                    first.crouchTicks = 16;
                    second.crouchTicks = 16;
                    first.friendshipGestureCooldown = 240;
                    second.friendshipGestureCooldown = 240;
                }
            }
        }
    }

    private static void assignNearestSettlement(BotBrain brain) {
        Settlement closest = null;
        double bestDistance = 48.0 * 48.0;
        for (Settlement settlement : SETTLEMENTS.values()) {
            if (settlement.level() != brain.level) continue;
            double distance = settlement.center().distSqr(brain.player.blockPosition());
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = settlement;
            }
        }
        if (closest != null) {
            closest.addMember(brain.player.getUUID(), brain.profession);
            brain.settlement = closest;
        }
    }

    private static void registerSettlement(BotBrain founder, BlockPos center) {
        Settlement settlement = null;
        for (Settlement candidate : SETTLEMENTS.values()) {
            if (candidate.level() == founder.level
                    && candidate.center().distSqr(center) <= 48 * 48) {
                settlement = candidate;
                break;
            }
        }
        if (settlement == null) {
            String[] names = {"Oakridge", "Riverstead", "Stonehaven", "Pinewatch", "Meadowbrook", "Ashford"};
            String name = names[RANDOM.nextInt(names.length)];
            settlement = new Settlement(name, founder.level, center);
            SETTLEMENTS.put(settlement.id(), settlement);
            founder.level.getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("A new settlement has been founded: " + name + "!"),
                    false
            );
        }
        settlement.addMember(founder.player.getUUID(), founder.profession);
        founder.settlement = settlement;
        settlement.moveCenter(center);
        for (BotBrain citizen : BOTS.values()) {
            if (citizen == founder || citizen.level != founder.level) continue;
            if (citizen.player.blockPosition().distSqr(center) <= 48 * 48) {
                settlement.addMember(citizen.player.getUUID(), citizen.profession);
                citizen.settlement = settlement;
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
        private int friendshipGestureCooldown;
        private final Map<UUID, Integer> relationships = new HashMap<>();
        private BlockPos wanderTarget;
        private int farmCooldown;
        private BlockPos miningTarget;
        private int miningProgress;
        private final Personality personality = Personality.random(RANDOM);
        private final Personality.Profession profession = personality.preferredProfession();
        private Settlement settlement;
        private List<BlockPos> navigationPath = new ArrayList<>();
        private BlockPos pathDestination;
        private int pathRecalculationCooldown;

        private BotBrain(FakePlayer player, ServerLevel level) {
            this.player = player;
            this.level = level;
        }

        private void tick(int gameTick) {
            if (actionCooldown > 0) actionCooldown--;
            if (scanCooldown > 0) scanCooldown--;
            if (socialCooldown > 0) socialCooldown--;
            if (friendshipGestureCooldown > 0) friendshipGestureCooldown--;
            if (farmCooldown > 0) farmCooldown--;
            if (crouchTicks > 0) { crouchTicks--; player.setShiftKeyDown(true); } else player.setShiftKeyDown(false);
            handleNearbyPlayers();

            if (state == State.BUILDING) {
                buildShelter(gameTick);
                return;
            }

            if (target == null || scanCooldown == 0) {
                if (target == null || player.blockPosition().distSqr(target) < 4 || scanCooldown == 0) {
                    int searchRadius = 14 + Math.round(personality.curiosity() * 12.0F);
                    if (profession == Personality.Profession.FARMER
                            && countItem(Items.WHEAT_SEEDS) < 8) {
                        target = findNearestGrass(24);
                    }
                    if (target == null) target = findNearestTreeLog(searchRadius);
                    if (target == null && countItem(Items.WHEAT_SEEDS) < 4) target = findNearestGrass(24);
                    if (target == null) {
                        target = null;
                        state = State.EXPLORING;
                        if (wanderTarget == null || player.blockPosition().distSqr(wanderTarget) < 9) {
                            int wanderRadius = 12 + Math.round(personality.curiosity() * 24)
                                    + (profession == Personality.Profession.SCOUT ? 12 : 0);
                            int dx = RANDOM.nextInt(wanderRadius * 2 + 1) - wanderRadius;
                            int dz = RANDOM.nextInt(wanderRadius * 2 + 1) - wanderRadius;
                            wanderTarget = player.blockPosition().offset(dx, 0, dz);
                        }
                    } else {
                        state = State.SEARCHING;
                    }
                    scanCooldown = 100;
                }
            }

            if (target != null) {
                lookAtBlock(target);
                if (player.distanceToSqr(Vec3.atCenterOf(target)) <= 4.0) {
                    if (actionCooldown == 0) {
                        boolean finished = gatherTarget();
                        actionCooldown = finished ? 8 : 1;
                        if (finished) target = null;
                    }
                } else {
                    walkToward(target);
                }
            } else if (state == State.EXPLORING && wanderTarget != null) {
                walkToward(wanderTarget);
                if (player.blockPosition().distSqr(wanderTarget) < 9) {
                    wanderTarget = null;
                    scanCooldown = 0;
                }
                if (gameTick % 40 == 0) lookAround(gameTick);
            } else {
                lookAround(gameTick);
            }

            convertLogsToPlanks();
            advanceCrafting();
            if (profession == Personality.Profession.FARMER
                    || player.getFoodData().getFoodLevel() <= 12) {
                manageFoodAndFarm(gameTick);
            }
            int shelterMaterialGoal = profession == Personality.Profession.BUILDER ? 17 : 21;
            if (countPlanks() >= shelterMaterialGoal && state != State.BUILDING) {
                BlockPos site = findShelterSite(personality.caution() > 0.7F ? 10 : 8);
                if (site != null) {
                    state = State.BUILDING;
                    shelterOrigin = site;
                    shelterIndex = 0;
                    target = null;
                    wanderTarget = null;
                }
            }
        }


        private int countItem(net.minecraft.world.item.Item item) {
            int total = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (stack.is(item)) total += stack.getCount();
            }
            return total;
        }

        private boolean isPlank(ItemStack stack) {
            net.minecraft.world.item.Item item = stack.getItem();
            return item == Items.OAK_PLANKS || item == Items.SPRUCE_PLANKS
                    || item == Items.BIRCH_PLANKS || item == Items.JUNGLE_PLANKS
                    || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS
                    || item == Items.MANGROVE_PLANKS || item == Items.CHERRY_PLANKS
                    || item == Items.CRIMSON_PLANKS || item == Items.WARPED_PLANKS;
        }

        private boolean removePlanks(int amount) {
            if (countPlanks() < amount) return false;
            int remaining = amount;
            for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!isPlank(stack)) continue;
                int removed = Math.min(remaining, stack.getCount());
                stack.shrink(removed);
                remaining -= removed;
            }
            return remaining == 0;
        }

        private boolean removeItems(net.minecraft.world.item.Item item, int amount) {
            if (countItem(item) < amount) return false;
            int remaining = amount;
            for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.is(item)) continue;
                int removed = Math.min(remaining, stack.getCount());
                stack.shrink(removed);
                remaining -= removed;
            }
            return remaining == 0;
        }

        private void advanceCrafting() {
            if (countItem(Items.CRAFTING_TABLE) == 0 && countItem(Items.OAK_PLANKS) >= 4) {
                removePlanks(4);
                player.getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
            }
            if (countItem(Items.STICK) < 2 && countPlanks() >= 2) {
                removePlanks(2);
                player.getInventory().add(new ItemStack(Items.STICK, 4));
            }
            if (countItem(Items.WOODEN_PICKAXE) == 0
                    && countPlanks() >= 3 && countItem(Items.STICK) >= 2) {
                removePlanks(3);
                removeItems(Items.STICK, 2);
                player.getInventory().add(new ItemStack(Items.WOODEN_PICKAXE));
            }
            if (countItem(Items.WOODEN_AXE) == 0
                    && countPlanks() >= 3 && countItem(Items.STICK) >= 2) {
                removePlanks(3);
                removeItems(Items.STICK, 2);
                player.getInventory().add(new ItemStack(Items.WOODEN_AXE));
            }
        }

        private void manageFoodAndFarm(int gameTick) {
            if (gameTick % 40 != 0 || farmCooldown > 0) return;
            farmCooldown = 40;
            for (BlockPos pos : BlockPos.betweenClosed(
                    player.blockPosition().offset(-8, -2, -8),
                    player.blockPosition().offset(8, 2, 8))) {
                BlockState cropState = level.getBlockState(pos);
                if (cropState.getBlock() instanceof CropBlock crop
                        && cropState.getValue(CropBlock.AGE) >= crop.getMaxAge()) {
                    level.destroyBlock(pos, false, player);
                    player.getInventory().add(new ItemStack(Items.WHEAT));
                    player.getInventory().add(new ItemStack(Items.WHEAT_SEEDS));
                    break;
                }
            }
            if (countItem(Items.WHEAT_SEEDS) > 0) {
                BlockPos base = player.blockPosition();
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos soil = base.offset(dx, -1, dz);
                        BlockPos cropPos = soil.above();
                        if (level.getBlockState(cropPos).isAir()
                                && (level.getBlockState(soil).is(Blocks.DIRT)
                                || level.getBlockState(soil).is(Blocks.GRASS_BLOCK))) {
                            level.setBlock(soil, Blocks.FARMLAND.defaultBlockState(), 3);
                            level.setBlock(cropPos, Blocks.WHEAT.defaultBlockState(), 3);
                            removeItems(Items.WHEAT_SEEDS, 1);
                            return;
                        }
                    }
                }
            }
            if (countItem(Items.WHEAT) >= 3) {
                removeItems(Items.WHEAT, 3);
                player.getInventory().add(new ItemStack(Items.BREAD));
            }
            if (player.getFoodData().getFoodLevel() <= 14 && countItem(Items.BREAD) > 0) {
                removeItems(Items.BREAD, 1);
                player.getFoodData().setFoodLevel(Math.min(20, player.getFoodData().getFoodLevel() + 5));
            }
        }


        private BlockPos findNearestGrass(int radius) {
            BlockPos center = player.blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    for (int y = -2; y <= 3; y++) {
                        BlockPos pos = center.offset(x, y, z);
                        if (!level.hasChunkAt(pos)) continue;
                        BlockState state = level.getBlockState(pos);
                        if (state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)) {
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

        private BlockPos findNearestTreeLog(int radius) {
            BlockPos center = player.blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
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
                        boolean easy = false; // Do not mine random terrain blocks.
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
            if (destination == null) return;
            if (pathRecalculationCooldown > 0) pathRecalculationCooldown--;

            if (!destination.equals(pathDestination)
                    || (navigationPath.isEmpty() && pathRecalculationCooldown == 0)) {
                pathDestination = destination.immutable();
                navigationPath = GroundPathfinder.findPath(level, player.blockPosition(), destination);
                pathRecalculationCooldown = 20;
            }

            while (!navigationPath.isEmpty()) {
                BlockPos waypoint = navigationPath.get(0);
                double dx = waypoint.getX() + 0.5 - player.getX();
                double dz = waypoint.getZ() + 0.5 - player.getZ();
                double dy = waypoint.getY() - player.getY();
                if (dx * dx + dz * dz < 0.16 && Math.abs(dy) < 0.65) {
                    navigationPath.remove(0);
                } else {
                    break;
                }
            }

            if (navigationPath.isEmpty()) {
                Vec3 velocity = player.getDeltaMovement();
                player.setDeltaMovement(velocity.x * 0.35, velocity.y, velocity.z * 0.35);
                player.setSprinting(false);
                return;
            }

            BlockPos waypoint = navigationPath.get(0);
            Vec3 offset = new Vec3(
                    waypoint.getX() + 0.5 - player.getX(),
                    0,
                    waypoint.getZ() + 0.5 - player.getZ()
            );
            double distance = offset.length();
            if (distance < 0.05) return;

            Vec3 desired = offset.normalize().scale(Math.min(0.105, distance * 0.18));
            float desiredYaw = (float) (Math.atan2(-desired.x, desired.z) * (180.0 / Math.PI));
            float yawDelta = net.minecraft.util.Mth.wrapDegrees(desiredYaw - player.getYRot());
            player.setYRot(player.getYRot()
                    + net.minecraft.util.Mth.clamp(yawDelta, -5.0F, 5.0F));
            player.setYHeadRot(player.getYRot());
            player.yBodyRot = player.getYRot();

            Vec3 velocity = player.getDeltaMovement();
            Vec3 horizontal = new Vec3(
                    velocity.x * 0.62 + desired.x * 0.38,
                    0,
                    velocity.z * 0.62 + desired.z * 0.38
            );
            double vertical = player.onGround() ? 0.0 : Math.max(-0.45, velocity.y - 0.08);

            if (waypoint.getY() > player.blockPosition().getY() && player.onGround()) {
                player.jumpFromGround();
                vertical = 0.42;
            }

            Vec3 motion = new Vec3(horizontal.x, vertical, horizontal.z);
            if (!level.noCollision(player, player.getBoundingBox().move(horizontal))) {
                navigationPath.clear();
                pathRecalculationCooldown = 0;
                player.setDeltaMovement(0, vertical, 0);
                return;
            }

            player.move(MoverType.SELF, motion);
            player.setDeltaMovement(motion);
            player.setSprinting(distance > 3.0 && player.onGround());
        }

        private boolean gatherTarget() {
            if (target == null) return true;
            lookAtBlock(target);
            if (player.distanceToSqr(Vec3.atCenterOf(target)) > 4.0) return false;
            BlockState state = level.getBlockState(target);
            if (state.isAir() || state.getDestroySpeed(level, target) < 0
                    || !hasClearLineOfSight(target)) {
                miningTarget = null;
                miningProgress = 0;
                return true;
            }

            if (!target.equals(miningTarget)) {
                miningTarget = target.immutable();
                miningProgress = 0;
            }

            // Require visible, timed mining progress instead of deleting blocks on contact.
            float hardness = state.getDestroySpeed(level, target);
            boolean hasAxe = countItem(Items.WOODEN_AXE) > 0;
            int requiredTicks = Math.max(4, Math.min(120,
                    (int) Math.ceil(hardness * (hasAxe && state.is(BlockTags.LOGS) ? 7.0F : 30.0F))));
            miningProgress++;
            if (miningProgress % 5 == 0) {
                player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            if (miningProgress < requiredTicks) return false;

            Block itemBlock = state.getBlock();
            boolean broken = level.destroyBlock(target, false, player);
            miningTarget = null;
            miningProgress = 0;
            if (broken) {
                ItemStack collected = new ItemStack(itemBlock.asItem());
                if (!collected.isEmpty()) player.getInventory().add(collected);
                if ((state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS))
                        && RANDOM.nextInt(3) == 0 && countItem(Items.WHEAT_SEEDS) < 16) {
                    player.getInventory().add(new ItemStack(Items.WHEAT_SEEDS));
                }
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal("<" + player.getGameProfile().name() + "> I found some " + itemBlock.getName().getString().toLowerCase() + "."),
                        false
                );
            }
            return true;
        }

        private boolean hasClearLineOfSight(BlockPos pos) {
            Vec3 eye = player.getEyePosition();
            Vec3 aim = Vec3.atCenterOf(pos);
            BlockHitResult hit = level.clip(new ClipContext(
                    eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
            ));
            return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
        }

        private BlockPos findShelterSite(int radius) {
            BlockPos center = player.blockPosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos floor = center.offset(dx, -1, dz);
                    BlockPos origin = floor.above();
                    double distance = origin.distSqr(center);
                    if (distance < 16 || distance > radius * radius || distance >= bestDistance) continue;
                    boolean valid = true;
                    for (int x = -1; x <= 1 && valid; x++) {
                        for (int z = -1; z <= 1 && valid; z++) {
                            BlockPos ground = floor.offset(x, 0, z);
                            if (!level.getBlockState(ground).isSolid()
                                    || !level.getFluidState(ground).isEmpty()) {
                                valid = false;
                                break;
                            }
                            for (int y = 1; y <= 2; y++) {
                                BlockPos space = ground.above(y);
                                if (!level.getBlockState(space).isAir()
                                        || !level.getFluidState(space).isEmpty()) {
                                    valid = false;
                                    break;
                                }
                            }
                        }
                    }
                    if (valid) {
                        best = origin.immutable();
                        bestDistance = distance;
                    }
                }
            }
            return best;
        }

        private void lookAtBlock(BlockPos pos) {
            Vec3 delta = Vec3.atCenterOf(pos).subtract(player.getEyePosition());
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            float yaw = (float) (Math.atan2(-delta.x, delta.z) * (180.0 / Math.PI));
            float pitch = (float) (-Math.atan2(delta.y, horizontal) * (180.0 / Math.PI));
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(pitch);
        }

        private void convertLogsToPlanks() {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) continue;
                Block block = Block.byItem(stack.getItem());
                if (!block.defaultBlockState().is(BlockTags.LOGS)) continue;

                net.minecraft.world.item.Item plankItem = Items.OAK_PLANKS;
                if (block == Blocks.SPRUCE_LOG || block == Blocks.SPRUCE_WOOD
                        || block == Blocks.STRIPPED_SPRUCE_LOG || block == Blocks.STRIPPED_SPRUCE_WOOD) {
                    plankItem = Items.SPRUCE_PLANKS;
                } else if (block == Blocks.BIRCH_LOG || block == Blocks.BIRCH_WOOD
                        || block == Blocks.STRIPPED_BIRCH_LOG || block == Blocks.STRIPPED_BIRCH_WOOD) {
                    plankItem = Items.BIRCH_PLANKS;
                } else if (block == Blocks.JUNGLE_LOG || block == Blocks.JUNGLE_WOOD
                        || block == Blocks.STRIPPED_JUNGLE_LOG || block == Blocks.STRIPPED_JUNGLE_WOOD) {
                    plankItem = Items.JUNGLE_PLANKS;
                } else if (block == Blocks.ACACIA_LOG || block == Blocks.ACACIA_WOOD
                        || block == Blocks.STRIPPED_ACACIA_LOG || block == Blocks.STRIPPED_ACACIA_WOOD) {
                    plankItem = Items.ACACIA_PLANKS;
                } else if (block == Blocks.DARK_OAK_LOG || block == Blocks.DARK_OAK_WOOD
                        || block == Blocks.STRIPPED_DARK_OAK_LOG || block == Blocks.STRIPPED_DARK_OAK_WOOD) {
                    plankItem = Items.DARK_OAK_PLANKS;
                } else if (block == Blocks.MANGROVE_LOG || block == Blocks.MANGROVE_WOOD
                        || block == Blocks.STRIPPED_MANGROVE_LOG || block == Blocks.STRIPPED_MANGROVE_WOOD) {
                    plankItem = Items.MANGROVE_PLANKS;
                } else if (block == Blocks.CHERRY_LOG || block == Blocks.CHERRY_WOOD
                        || block == Blocks.STRIPPED_CHERRY_LOG || block == Blocks.STRIPPED_CHERRY_WOOD) {
                    plankItem = Items.CHERRY_PLANKS;
                } else if (block == Blocks.CRIMSON_STEM || block == Blocks.CRIMSON_HYPHAE
                        || block == Blocks.STRIPPED_CRIMSON_STEM || block == Blocks.STRIPPED_CRIMSON_HYPHAE) {
                    plankItem = Items.CRIMSON_PLANKS;
                } else if (block == Blocks.WARPED_STEM || block == Blocks.WARPED_HYPHAE
                        || block == Blocks.STRIPPED_WARPED_STEM || block == Blocks.STRIPPED_WARPED_HYPHAE) {
                    plankItem = Items.WARPED_PLANKS;
                }
                int amount = stack.getCount() * 4;
                stack.shrink(stack.getCount());
                int sharedAmount = settlement == null ? 0 : amount / 4;
                if (sharedAmount > 0) settlement.deposit("planks", sharedAmount);
                int personalAmount = amount - sharedAmount;
                if (personalAmount > 0) player.getInventory().add(new ItemStack(plankItem, personalAmount));
            }
        }

        private int countPlanks() {
            int total = 0;
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (isPlank(stack)) total += stack.getCount();
            }
            return total;
        }

        private int countGatheredBlocks() {
            int total = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                if (!stack.isEmpty() && Block.byItem(stack.getItem()) != Blocks.AIR) total += stack.getCount();
            }
            return total;
        }

        private void buildShelter(int gameTick) {
            if (actionCooldown > 0) return;
            if (shelterOrigin == null) shelterOrigin = findShelterSite(8);
            if (shelterOrigin == null) {
                state = State.EXPLORING;
                return;
            }
            if (shelterIndex >= SHELTER_LAYOUT.size()) {
                state = State.EXPLORING;
                shelterOrigin = null;
                target = null;
                player.setSprinting(false);
                registerSettlement(this, shelterOrigin);
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal("<" + player.getGameProfile().name() + "> Our shelter is ready. Welcome to " + settlement.name() + "!"),
                        false
                );
                return;
            }

            BlockPos placeAt = shelterOrigin.offset(
                    SHELTER_LAYOUT.get(shelterIndex).getX(),
                    SHELTER_LAYOUT.get(shelterIndex).getY(),
                    SHELTER_LAYOUT.get(shelterIndex).getZ()
            );
            if (!level.getBlockState(placeAt).isAir()) {
                shelterIndex++;
                return;
            }

            if (countPlanks() == 0 && settlement != null && settlement.stock("planks") > 0) {
                int shared = settlement.withdraw("planks", Math.min(4, settlement.stock("planks")));
                if (shared > 0) player.getInventory().add(new ItemStack(Items.OAK_PLANKS, shared));
            }

            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty() || !isPlank(stack)) continue;

                if (player.distanceToSqr(Vec3.atCenterOf(placeAt)) > 20.25) {
                    walkToward(placeAt);
                    return;
                }
                lookAtBlock(placeAt);
                if (placeShelterBlock(placeAt, stack)) {
                    shelterIndex++;
                    actionCooldown = 8;
                }
                return;
            }

            state = State.SEARCHING;
            target = null;
        }

        private boolean placeShelterBlock(BlockPos placeAt, ItemStack stack) {
            Block block = Block.byItem(stack.getItem());
            if (block == Blocks.AIR || !level.getBlockState(placeAt).isAir()) return false;

            // Use the server's normal player block-interaction pipeline, not setBlock().
            for (Direction face : Direction.values()) {
                BlockPos support = placeAt.relative(face.getOpposite());
                BlockState supportState = level.getBlockState(support);
                if (!supportState.isFaceSturdy(level, support, face)) continue;

                Vec3 hitLocation = Vec3.atCenterOf(support).add(
                        face.getStepX() * 0.499,
                        face.getStepY() * 0.499,
                        face.getStepZ() * 0.499
                );
                BlockHitResult sight = level.clip(new ClipContext(
                        player.getEyePosition(), hitLocation,
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player
                ));
                if (sight.getType() != HitResult.Type.BLOCK || !sight.getBlockPos().equals(support)) continue;

                BlockHitResult hit = new BlockHitResult(hitLocation, face, support, false);
                player.gameMode.useItemOn(
                        player, level, stack, net.minecraft.world.InteractionHand.MAIN_HAND, hit
                );
                if (level.getBlockState(placeAt).is(block)) {
                    player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    return true;
                }
            }
            return false;
        }

        private void handleNearbyPlayers() {
            if (socialCooldown > 0) return;
            // Social gestures are idle-only: never turn away from a mining/building target.
            if (target != null || state == State.BUILDING) return;
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
                            Component.literal(player.getGameProfile().name() + " and " + nearby.getGameProfile().name() + " are teaming up!"),
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
