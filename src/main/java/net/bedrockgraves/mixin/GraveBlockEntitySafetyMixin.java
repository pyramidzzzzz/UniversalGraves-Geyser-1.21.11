package net.bedrockgraves.mixin;

import eu.pb4.graves.grave.Grave;
import eu.pb4.graves.registry.GraveBlockEntity;
import net.bedrockgraves.BedrockGravesAddon;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Prevents graves from self-destructing during slow server load when grave data
 * hasn't been fully initialized yet. Allows legitimate destruction once the grave
 * is properly loaded and marked as removed.
 */
@Mixin(GraveBlockEntity.class)
public class GraveBlockEntitySafetyMixin {
    @Redirect(
            method = "tick(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/block/entity/BlockEntity;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z", ordinal = 0),
            remap = true)
    private static boolean bedrockgraves$skipSelfDestruct(World world, BlockPos pos, BlockState state) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof GraveBlockEntity gbe)) {
            // Not a grave block entity, allow the state change
            return world.setBlockState(pos, state);
        }

        Grave grave = gbe.getGrave();
        if (grave == null) {
            // Grave data not loaded yet - skip destruction to prevent item loss
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Skipping grave destruction at {} - data not loaded", pos);
            return false;
        }

        if (grave.isRemoved()) {
            // Grave is legitimately removed (player collected items), allow destruction
            return world.setBlockState(pos, state);
        }

        // Grave exists but isn't marked as removed - skip destruction
        BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Skipping grave destruction at {} - grave not marked removed", pos);
        return false;
    }
}
