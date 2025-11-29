package net.bedrockgraves.mixin;

import net.bedrockgraves.BedrockGravesAddon;
import net.bedrockgraves.gui.BedrockGuiTranslator;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

/**
 * Intercepts inventory/GUI packets sent to Bedrock players and translates
 * custom player head items (used by Polymer/SGUI) to recognizable vanilla items.
 */
@Mixin(ServerCommonNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {

    private ServerPlayerEntity bedrockgraves$getPlayer() {
        if (((Object) this) instanceof ServerPlayNetworkHandler handler) {
            return handler.player;
        }
        return null;
    }

    /**
     * Intercepts single slot updates and translates items for Bedrock players.
     */
    @ModifyVariable(
            method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Packet<?> bedrockgraves$translateSlotPacket(Packet<?> packet) {
        ServerPlayerEntity player = this.bedrockgraves$getPlayer();
        if (player == null) {
            return packet;
        }

        if (!(packet instanceof ScreenHandlerSlotUpdateS2CPacket slotPacket)) {
            return packet;
        }

        if (!BedrockGuiTranslator.isBedrockPlayer(player.getUuid())) {
            return packet;
        }

        try {
            ItemStack original = slotPacket.getStack();
            ItemStack translated = BedrockGuiTranslator.translateForBedrock(original);

            if (translated != original) {
                return new ScreenHandlerSlotUpdateS2CPacket(
                        slotPacket.getSyncId(),
                        slotPacket.getRevision(),
                        slotPacket.getSlot(),
                        translated
                );
            }
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to translate slot packet: {}", e.getMessage());
        }

        return packet;
    }

    /**
     * Intercepts full inventory updates and translates items for Bedrock players.
     */
    @ModifyVariable(
            method = "sendPacket(Lnet/minecraft/network/packet/Packet;)V",
            at = @At("HEAD"),
            argsOnly = true
    )
    private Packet<?> bedrockgraves$translateInventoryPacket(Packet<?> packet) {
        ServerPlayerEntity player = this.bedrockgraves$getPlayer();
        if (player == null) {
            return packet;
        }

        if (!(packet instanceof InventoryS2CPacket invPacket)) {
            return packet;
        }

        if (!BedrockGuiTranslator.isBedrockPlayer(player.getUuid())) {
            return packet;
        }

        try {
            List<ItemStack> originalStacks = invPacket.contents();
            List<ItemStack> translatedStacks = new ArrayList<>(originalStacks.size());
            boolean anyChanged = false;

            for (ItemStack stack : originalStacks) {
                ItemStack translated = BedrockGuiTranslator.translateForBedrock(stack);
                translatedStacks.add(translated);
                if (translated != stack) {
                    anyChanged = true;
                }
            }

            if (anyChanged) {
                ItemStack cursorTranslated = BedrockGuiTranslator.translateForBedrock(invPacket.cursorStack());
                return new InventoryS2CPacket(
                        invPacket.syncId(),
                        invPacket.revision(),
                        translatedStacks,
                        cursorTranslated
                );
            }
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to translate inventory packet: {}", e.getMessage());
        }

        return packet;
    }
}
