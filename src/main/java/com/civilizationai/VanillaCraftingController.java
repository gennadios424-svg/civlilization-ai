package com.civilizationai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Uses the world's live vanilla RecipeManager for every craft. Ingredient
 * consumption happens only after a matching recipe has assembled an output.
 */
public final class VanillaCraftingController {
    private static final Item[] PLANKS = {
            Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
            Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS,
            Items.CHERRY_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS
    };

    private VanillaCraftingController() {}

    public static void tick(ServerPlayer player, ServerLevel level) {
        craftIfPossible(player, level, false);
        if (hasCraftingTableNearby(player, level, 12)) {
            craftIfPossible(player, level, true);
        } else if (count(player, Items.CRAFTING_TABLE) > 0) {
            placeCraftingTable(player, level);
        }
    }

    private static void craftIfPossible(ServerPlayer player, ServerLevel level, boolean tableAvailable) {
        // Convert one log at a time through the live 1x1 vanilla recipe.
        craftLogs(player, level);

        // Starter progression is recipe-backed. The recipe manager validates
        // the grid and computes output and remainders before ingredients shrink.
        for (Item plank : PLANKS) {
            if (count(player, Items.CRAFTING_TABLE) == 0 && !hasCraftingTableNearby(player, level, 12)) {
                tryPattern(player, level, 2, 2, new Item[]{plank, plank, plank, plank});
            }
            if (count(player, Items.STICK) < 2) {
                tryPattern(player, level, 2, 2, new Item[]{plank, null, plank, null});
            }
            if (tableAvailable && count(player, Items.WOODEN_PICKAXE) == 0) {
                tryPattern(player, level, 3, 3, new Item[]{
                        plank, plank, plank,
                        null, Items.STICK, null,
                        null, Items.STICK, null
                });
            }
            if (tableAvailable && count(player, Items.WOODEN_HOE) == 0) {
                tryPattern(player, level, 3, 3, new Item[]{
                        plank, plank, null,
                        null, Items.STICK, null,
                        null, Items.STICK, null
                });
            }
        }

        if (!tableAvailable) return;

        if (count(player, Items.STONE_PICKAXE) == 0 && count(player, Items.COBBLESTONE) >= 3) {
            tryPattern(player, level, 3, 3, new Item[]{
                    Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                    null, Items.STICK, null,
                    null, Items.STICK, null
            });
        }
        if (count(player, Items.FURNACE) == 0 && count(player, Items.COBBLESTONE) >= 8) {
            tryPattern(player, level, 3, 3, new Item[]{
                    Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE,
                    Items.COBBLESTONE, null, Items.COBBLESTONE,
                    Items.COBBLESTONE, Items.COBBLESTONE, Items.COBBLESTONE
            });
        }
        if (count(player, Items.CHEST) == 0) {
            for (Item plank : PLANKS) {
                if (tryPattern(player, level, 3, 3, new Item[]{
                        plank, plank, plank,
                        plank, null, plank,
                        plank, plank, plank
                })) break;
            }
        }
        if (count(player, Items.OAK_DOOR) == 0) {
            for (Item plank : PLANKS) {
                if (tryPattern(player, level, 3, 3, new Item[]{
                        plank, plank, null,
                        plank, plank, null,
                        plank, plank, null
                })) break;
            }
        }
        if (count(player, Items.BREAD) < 3 && count(player, Items.WHEAT) >= 3) {
            tryPattern(player, level, 3, 3, new Item[]{
                    Items.WHEAT, Items.WHEAT, Items.WHEAT,
                    null, null, null,
                    null, null, null
            });
        }
        if (count(player, Items.TORCH) < 8 && count(player, Items.COAL) > 0 && count(player, Items.STICK) > 0) {
            tryPattern(player, level, 2, 2, new Item[]{
                    Items.COAL, null, Items.STICK, null
            });
        }
    }

    private static void craftLogs(ServerPlayer player, ServerLevel level) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack logStack = player.getInventory().getItem(slot);
            if (logStack.isEmpty()) continue;
            if (!net.minecraft.world.level.block.Block.byItem(logStack.getItem())
                    .defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)) continue;

            CraftingInput input = CraftingInput.of(1, 1, List.of(new ItemStack(logStack.getItem())));
            Optional<RecipeHolder<CraftingRecipe>> match =
                    level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
            if (match.isEmpty()) continue;
            ItemStack output = match.get().value().assemble(input, level.registryAccess());
            if (output.isEmpty() || !canFit(player, output)) return;

            var remainders = match.get().value().getRecipeRemainders(input);
            logStack.shrink(1);
            for (ItemStack remainder : remainders) {
                if (!remainder.isEmpty()) player.getInventory().add(remainder.copy());
            }
            player.getInventory().add(output.copy());
            player.inventoryMenu.broadcastChanges();
        }
    }

    private static boolean tryPattern(ServerPlayer player, ServerLevel level,
                                      int width, int height, Item[] pattern) {
        List<ItemStack> grid = new ArrayList<>(pattern.length);
        List<Integer> inventorySlots = new ArrayList<>(pattern.length);
        for (Item item : pattern) {
            if (item == null) {
                grid.add(ItemStack.EMPTY);
                inventorySlots.add(-1);
                continue;
            }
            int slot = findSlot(player, item);
            if (slot < 0) return false;
            grid.add(new ItemStack(item));
            inventorySlots.add(slot);
        }

        CraftingInput input = CraftingInput.of(width, height, grid);
        Optional<RecipeHolder<CraftingRecipe>> match =
                level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level);
        if (match.isEmpty()) return false;

        ItemStack output = match.get().value().assemble(input, level.registryAccess());
        if (output.isEmpty() || !canFit(player, output)) return false;

        var remainders = level.getServer().getRecipeManager()
                .getRemainingItemsFor(RecipeType.CRAFTING, input, level);

        for (int slot : inventorySlots) {
            if (slot >= 0) player.getInventory().getItem(slot).shrink(1);
        }
        for (ItemStack remainder : remainders) {
            if (!remainder.isEmpty()) player.getInventory().add(remainder.copy());
        }
        player.getInventory().add(output.copy());
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    private static int findSlot(ServerPlayer player, Item item) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private static int count(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean canFit(ServerPlayer player, ItemStack output) {
        if (player.getInventory().getFreeSlot() >= 0) return true;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (ItemStack.isSameItemSameComponents(stack, output)
                    && stack.getCount() + output.getCount() <= stack.getMaxStackSize()) return true;
        }
        return false;
    }

    private static boolean hasCraftingTableNearby(ServerPlayer player, ServerLevel level, int radius) {
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private static void placeCraftingTable(ServerPlayer player, ServerLevel level) {
        ItemStack table = null;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (candidate.is(Items.CRAFTING_TABLE)) {
                table = candidate;
                break;
            }
        }
        if (table == null) return;

        BlockPos feet = player.blockPosition();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos support = feet.relative(direction).below();
            BlockPos place = support.above();
            if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)
                    || !level.getBlockState(place).canBeReplaced()
                    || !level.getBlockState(place.above()).getCollisionShape(level, place.above()).isEmpty()
                    || player.distanceToSqr(Vec3.atCenterOf(place)) > 16.0) continue;

            MovementController.face(player, Vec3.atCenterOf(place));
            BlockHitResult hit = new BlockHitResult(
                    Vec3.atCenterOf(support).add(0, 0.499, 0),
                    Direction.UP, support, false
            );
            player.gameMode.useItemOn(player, level, table, InteractionHand.MAIN_HAND, hit);
            if (level.getBlockState(place).is(Blocks.CRAFTING_TABLE)) {
                player.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }
    }
}
