package com.civilizationai;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Hunger decisions backed by real food items and the player's vanilla FoodData. */
public final class SurvivalController {
    private SurvivalController() {}

    public static boolean eatFromInventory(ServerPlayer player) {
        if (player.getFoodData().getFoodLevel() >= 18) return false;

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            int nutrition = nutrition(stack);
            float saturation = saturation(stack);
            if (nutrition <= 0 || stack.isEmpty()) continue;

            stack.shrink(1);
            player.getFoodData().eat(nutrition, saturation);
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            player.inventoryMenu.broadcastChanges();
            return true;
        }
        return false;
    }

    public static boolean hasFood(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (nutrition(player.getInventory().getItem(slot)) > 0) return true;
        }
        return false;
    }

    private static int nutrition(ItemStack stack) {
        if (stack.is(Items.COOKED_BEEF) || stack.is(Items.COOKED_PORKCHOP)) return 8;
        if (stack.is(Items.COOKED_MUTTON) || stack.is(Items.COOKED_SALMON)) return 6;
        if (stack.is(Items.COOKED_CHICKEN) || stack.is(Items.COOKED_COD)) return 5;
        if (stack.is(Items.BREAD) || stack.is(Items.BAKED_POTATO)) return 5;
        if (stack.is(Items.APPLE)) return 4;
        if (stack.is(Items.CARROT) || stack.is(Items.POTATO)) return 3;
        if (stack.is(Items.SWEET_BERRIES)) return 2;
        if (stack.is(Items.BEEF) || stack.is(Items.PORKCHOP) || stack.is(Items.MUTTON)
                || stack.is(Items.CHICKEN) || stack.is(Items.RABBIT)) return 2;
        if (stack.is(Items.PUMPKIN_PIE)) return 8;
        return 0;
    }

    private static float saturation(ItemStack stack) {
        if (stack.is(Items.COOKED_BEEF) || stack.is(Items.COOKED_PORKCHOP)) return 0.8F;
        if (stack.is(Items.COOKED_MUTTON) || stack.is(Items.COOKED_SALMON)) return 0.8F;
        if (stack.is(Items.BREAD) || stack.is(Items.BAKED_POTATO)) return 0.6F;
        if (stack.is(Items.COOKED_CHICKEN) || stack.is(Items.COOKED_COD)) return 0.6F;
        if (stack.is(Items.APPLE)) return 0.3F;
        if (stack.is(Items.CARROT) || stack.is(Items.POTATO)) return 0.6F;
        return 0.1F;
    }
}
