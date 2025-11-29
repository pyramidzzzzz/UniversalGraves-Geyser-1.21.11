package net.bedrockgraves.mixin;

import eu.pb4.graves.registry.GraveBlockEntity;
import net.bedrockgraves.overlay.BedrockOverlayManager;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GraveBlockEntity.class)
public abstract class GraveBlockEntityMixin extends BlockEntity {
    public GraveBlockEntityMixin(BlockPos pos, BlockState state) {
        super(null, pos, state);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private static <T extends BlockEntity> void bedrockgraves$tick(World world, BlockPos pos, BlockState state, T t, CallbackInfo ci) {
        if (!world.isClient() && t instanceof GraveBlockEntity gbe) {
            BedrockOverlayManager.tick(gbe);
        }
    }

    @Inject(method = "onBlockReplaced", at = @At("HEAD"))
    private void bedrockgraves$onReplaced(BlockPos pos, BlockState oldState, CallbackInfo ci) {
        if (this.world != null && !this.world.isClient()) {
            BedrockOverlayManager.clearForWorld((net.minecraft.server.world.ServerWorld) this.world, pos);
        }
    }
}
