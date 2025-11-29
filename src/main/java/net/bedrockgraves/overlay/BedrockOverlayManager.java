package net.bedrockgraves.overlay;

import eu.pb4.graves.config.ConfigManager;
import eu.pb4.graves.grave.Grave;
import eu.pb4.graves.grave.GraveManager;
import eu.pb4.graves.registry.GraveBlockEntity;
import net.bedrockgraves.BedrockGravesAddon;
import eu.pb4.graves.ui.GraveGui;
import eu.pb4.sgui.api.GuiHelpers;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SkullBlock;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.passive.FoxEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

public final class BedrockOverlayManager {
    // Entity ID counter - uses negative range to avoid conflicts with real entities
    private static final AtomicInteger ENTITY_IDS = new AtomicInteger(-2_000_000_000);

    // Active overlay tracking: player UUID -> (grave position -> overlay info)
    private static final Map<UUID, Map<BlockPos, HoloInfo>> ACTIVE = new ConcurrentHashMap<>();

    // Timing constants
    private static final int UPDATE_PERIOD_TICKS = 5;           // Update every 0.25 seconds
    private static final long HIDE_DELAY_MS = 200;              // Delay before hiding overlay
    private static final long RESPAWN_INTERVAL_MS = 30_000;     // Respawn entities every 30s

    // Distance constants
    private static final double MAX_DISTANCE_SQ = 48 * 48;      // Max render distance (48 blocks)
    private static final double GRACE_DISTANCE_SQ = 2 * 2;      // Skip LOS check within 2 blocks

    // Position offsets for visual elements
    private static final double OFFSET_Y = 1.0;                 // Lift text above grave block
    private static final double TEXT_Y_EPSILON = 0.01;          // Micro-lift to avoid physics ejection
    private static final double FOX_OFFSET_X = 0.45;
    private static final double FOX_OFFSET_Z = 0.0;
    private static final double FOX_OFFSET_Y = -0.14;           // Sink item closer to ground

    // Floodgate API state (lazy-loaded via reflection)
    private static boolean floodgateChecked = false;
    private static Method floodgateCheckMethod;
    private static Object floodgateApiInstance;

    private record HoloInfo(int textEntityId, int itemFoxId, Identifier worldId, long lastSpawnMs, long lastSeenMs, long lastHeadSendMs) {
    }

    private BedrockOverlayManager() {
    }

    public static void clearPlayer(UUID uuid) {
        ACTIVE.remove(uuid);
    }

    public static void tick(GraveBlockEntity be) {
        if (be.getWorld() == null || be.getWorld().isClient()) {
            return;
        }
        ServerWorld world = (ServerWorld) be.getWorld();
        BlockPos pos = be.getPos();
        Grave grave = be.getGrave();

        if (grave == null || grave.isRemoved()) {
            clearForWorld(world, pos);
            return;
        }

        if (world.getTime() % UPDATE_PERIOD_TICKS != 0) {
            return;
        }

        Identifier worldId = world.getRegistryKey().getValue();
        boolean hasBedrockViewer = false;
        ItemStack displayItem = pickItem(grave);

        for (ServerPlayerEntity player : world.getPlayers()) {
            boolean isBedrock = isFloodgateBedrock(player);
            Map<BlockPos, HoloInfo> byPos = ACTIVE.computeIfAbsent(player.getUuid(), k -> new ConcurrentHashMap<>());
            HoloInfo existing = byPos.get(pos);

            double distSq = player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            boolean inRange = distSq <= MAX_DISTANCE_SQ;
            boolean hasSight = !isBedrock || distSq <= GRACE_DISTANCE_SQ || hasLineOfSight(player, pos)
                    || isViewingGraveUi(player);
            long now = System.currentTimeMillis();

            if (!isBedrock || !inRange || !hasSight) {
                if (existing != null && now - existing.lastSeenMs() > HIDE_DELAY_MS) {
                    destroy(player, existing.textEntityId());
                    if (existing.itemFoxId() != -1) {
                        destroy(player, existing.itemFoxId());
                    }
                    byPos.remove(pos);
                }
                continue;
            }

            hasBedrockViewer = true;
            Text text = buildText(world, grave, pos);
            boolean needHead = existing == null || now - existing.lastHeadSendMs() > 1000;
            if (needHead) {
                sendHead(player, world, pos, grave);
            }

            if (existing == null) {
                int textId = spawnText(player, pos, text);
                int itemFoxId = spawnFox(player, pos, displayItem);
                byPos.put(pos, new HoloInfo(textId, itemFoxId, worldId, now, now, now));
            } else {
                boolean needsRespawn = now - existing.lastSpawnMs() > RESPAWN_INTERVAL_MS;
                int textId = existing.textEntityId();
                int itemFoxId = existing.itemFoxId();
                if (needsRespawn) {
                    destroy(player, textId);
                    if (itemFoxId != -1) {
                        destroy(player, itemFoxId);
                    }
                    textId = spawnText(player, pos, text);
                    itemFoxId = spawnFox(player, pos, displayItem);
                } else {
                    updateText(player, textId, text);
                    if (itemFoxId == -1) {
                        itemFoxId = spawnFox(player, pos, displayItem);
                    } else {
                        sendEquipAndHide(player, itemFoxId, displayItem);
                    }
                }
                byPos.put(pos, new HoloInfo(textId, itemFoxId, worldId, needsRespawn ? now : existing.lastSpawnMs(), now, needHead ? now : existing.lastHeadSendMs()));
            }
        }

        if (!hasBedrockViewer) {
            sendRealBlockStateToAll(world, pos);
        }
    }

    public static void clearForWorld(ServerWorld world, BlockPos pos) {
        Identifier worldId = world.getRegistryKey().getValue();
        for (ServerPlayerEntity player : world.getPlayers()) {
            Map<BlockPos, HoloInfo> byPos = ACTIVE.get(player.getUuid());
            if (byPos == null) {
                continue;
            }
            HoloInfo info = byPos.get(pos);
            if (info != null && info.worldId().equals(worldId)) {
                destroy(player, info.textEntityId());
                if (info.itemFoxId() != -1) {
                    destroy(player, info.itemFoxId());
                }
                byPos.remove(pos);
            }
        }
    }

    private static boolean isViewingGraveUi(ServerPlayerEntity player) {
        try {
            return GuiHelpers.getCurrentGui(player) instanceof GraveGui;
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to check grave UI state for {}: {}",
                    player.getName().getString(), e.getMessage());
            return false;
        }
    }

    private static void sendHead(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Grave grave) {
        BlockState headState = Blocks.SKELETON_SKULL.getDefaultState();
        if (headState.contains(SkullBlock.ROTATION)) {
            headState = headState.with(SkullBlock.ROTATION, world.getRandom().nextInt(16));
        }
        player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, headState));

        SkullBlockEntity skull = new SkullBlockEntity(pos, headState);
        skull.setWorld(world);
        var update = skull.toUpdatePacket();
        if (update != null) {
            player.networkHandler.sendPacket(update);
        }
    }

    private static void sendRealBlockState(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        BlockState realState = world.getBlockState(pos);
        player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, realState));
        var be = world.getBlockEntity(pos);
        if (be != null) {
            var update = be.toUpdatePacket();
            if (update != null) {
                player.networkHandler.sendPacket(update);
            }
        }
    }

    private static void destroy(ServerPlayerEntity player, int entityId) {
        player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(entityId));
    }

    private static void sendRealBlockStateToAll(ServerWorld world, BlockPos pos) {
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (isFloodgateBedrock(player)) {
                sendRealBlockState(player, world, pos);
            }
        }
    }

    private static int spawnText(ServerPlayerEntity player, BlockPos pos, Text name) {
        ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, (ServerWorld) player.getEntityWorld());
        stand.setPos(pos.getX() + 0.5, pos.getY() + OFFSET_Y + TEXT_Y_EPSILON, pos.getZ() + 0.5);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        forceMarker(stand, true);
        stand.setCustomNameVisible(true);
        stand.setCustomName(name);
        forceSmall(stand, false);

        int entityId = ENTITY_IDS.getAndDecrement();
        EntitySpawnS2CPacket spawnPacket = new EntitySpawnS2CPacket(
                entityId,
                UUID.randomUUID(),
                stand.getX(),
                stand.getY(),
                stand.getZ(),
                stand.getPitch(),
                stand.getYaw(),
                EntityType.ARMOR_STAND,
                0,
                Vec3d.ZERO,
                0);
        player.networkHandler.sendPacket(spawnPacket);
        List<DataTracker.SerializedEntry<?>> entries = stand.getDataTracker().getChangedEntries();
        player.networkHandler
                .sendPacket(new EntityTrackerUpdateS2CPacket(entityId, entries != null ? entries : List.of()));
        return entityId;
    }

    private static void updateText(ServerPlayerEntity player, int entityId, Text name) {
        ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, (ServerWorld) player.getEntityWorld());
        stand.setInvisible(true);
        stand.setNoGravity(true);
        forceMarker(stand, true);
        stand.setCustomNameVisible(true);
        stand.setCustomName(name);
        forceSmall(stand, false);
        List<DataTracker.SerializedEntry<?>> entries = stand.getDataTracker().getChangedEntries();
        player.networkHandler
                .sendPacket(new EntityTrackerUpdateS2CPacket(entityId, entries != null ? entries : List.of()));
    }

    private static int spawnFox(ServerPlayerEntity player, BlockPos pos, ItemStack stack) {
        FoxEntity fox = createBaseFox(player, pos, FOX_OFFSET_Y);

        int entityId = ENTITY_IDS.getAndDecrement();
        EntitySpawnS2CPacket spawnPacket = new EntitySpawnS2CPacket(
                entityId,
                UUID.randomUUID(),
                fox.getX(),
                fox.getY(),
                fox.getZ(),
                fox.getPitch(),
                fox.getYaw(),
                EntityType.FOX,
                0,
                Vec3d.ZERO,
                0);
        player.networkHandler.sendPacket(spawnPacket);
        sendFoxMeta(player, entityId, fox);

        scheduleDelayedEquip(player, entityId, stack, 2);
        return entityId;
    }

    private static void scheduleDelayedEquip(ServerPlayerEntity player, int entityId, ItemStack stack, int ticksDelay) {
        if (ticksDelay <= 0) {
            sendEquipAndHide(player, entityId, stack);
            return;
        }
        BedrockGravesAddon.DO_ON_NEXT_TICK.add(new Runnable() {
            int remaining = ticksDelay;

            @Override
            public void run() {
                if (--remaining <= 0) {
                    sendEquipAndHide(player, entityId, stack);
                } else {
                    BedrockGravesAddon.DO_ON_NEXT_TICK.add(this);
                }
            }
        });
    }

    private static void sendEquipAndHide(ServerPlayerEntity player, int entityId, ItemStack stack) {
        try {
            ItemStack send = stack.isEmpty() ? new ItemStack(Items.COMPASS) : stack.copyWithCount(1);
            var equipmentPacket = new EntityEquipmentUpdateS2CPacket(entityId,
                    List.of(new com.mojang.datafixers.util.Pair<>(EquipmentSlot.MAINHAND, send)));
            player.networkHandler.sendPacket(equipmentPacket);

            StatusEffectInstance invis = new StatusEffectInstance(StatusEffects.INVISIBILITY, Integer.MAX_VALUE, 0, true, false, false);
            player.networkHandler.sendPacket(new EntityStatusEffectS2CPacket(entityId, invis, true));
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("Failed to equip/hide fox: {}", e.toString());
        }
    }

    private static FoxEntity createBaseFox(ServerPlayerEntity player, BlockPos pos, double yOffset) {
        FoxEntity fox = new FoxEntity(EntityType.FOX, (ServerWorld) player.getEntityWorld());
        fox.setBaby(true);
        fox.setNoGravity(true);
        fox.setAiDisabled(true);
        fox.setInvulnerable(true);
        fox.setSitting(true);
        fox.setSilent(true);
        fox.setInvisible(true);
        fox.setBreedingAge(-24000);
        fox.refreshPositionAndAngles(
                pos.getX() + 0.5 + FOX_OFFSET_X,
                pos.getY() + yOffset,
                pos.getZ() + 0.5 + FOX_OFFSET_Z,
                0.0f,
                0.0f);
        return fox;
    }

    private static void sendFoxMeta(ServerPlayerEntity player, int entityId, FoxEntity fox) {
        var entries = fox.getDataTracker().getChangedEntries();
        player.networkHandler.sendPacket(new EntityTrackerUpdateS2CPacket(entityId, entries != null ? entries : List.of()));
    }

    private static Text buildText(ServerWorld world, Grave grave, BlockPos pos) {
        Map<String, Text> placeholders = world.getServer() != null ? grave.getPlaceholders(world.getServer())
                : Map.of();
        String playerName = placeholders.getOrDefault("player", Text.literal("<Unknown>")).getString();
        String death = placeholders.getOrDefault("death_cause", Text.empty()).getString();

        int itemCount = 0;
        for (var stack : grave.getItems()) {
            if (!stack.isEmpty()) {
                itemCount++;
            }
        }

        var config = ConfigManager.getConfig();
        long protectionTime = GraveManager.INSTANCE.getProtectionTime();
        long breakingTime = GraveManager.INSTANCE.getBreakingTime();

        String protection = protectionTime > -1
                ? config.getFormattedTime(
                        Math.max(0, grave.getTimeLeft((int) protectionTime, config.protection.useRealTime)))
                : config.texts.infinityText;
        String breaking = breakingTime > -1
                ? config.getFormattedTime(
                        Math.max(0, grave.getTimeLeft((int) breakingTime, config.protection.useRealTime)))
                : config.texts.infinityText;

        Text line1 = Text.literal("Grave of " + playerName).formatted(Formatting.YELLOW);
        Text line1b = Text.literal(death).formatted(Formatting.YELLOW);
        Text line2 = Text.literal("Items: " + itemCount + "  XP: " + grave.getXp()).formatted(Formatting.WHITE);
        Text line3 = Text.literal("Protected for: ").formatted(Formatting.BLUE)
                .append(Text.literal(protection).formatted(Formatting.WHITE));
        Text line4 = Text.literal("Breaks in: ").formatted(Formatting.RED)
                .append(Text.literal(breaking).formatted(Formatting.WHITE));

        return Text.empty()
                .append(line1)
                .append(Text.literal("\n"))
                .append(line1b)
                .append(Text.literal("\n"))
                .append(line2)
                .append(Text.literal("\n"))
                .append(line3)
                .append(Text.literal("\n"))
                .append(line4);
    }

    private static boolean hasLineOfSight(ServerPlayerEntity player, BlockPos pos) {
        Vec3d eye = player.getEyePos();
        Vec3d targetBase = Vec3d.ofCenter(pos).add(0, OFFSET_Y, 0);
        Vec3d[] targets = new Vec3d[] {
                targetBase,
                targetBase.add(0.4, 0, 0.4),
                targetBase.add(-0.4, 0, -0.4),
                targetBase.add(0, 0.5, 0),
                targetBase.add(0, -0.3, 0)
        };

        for (Vec3d target : targets) {
            RaycastContext ctx = new RaycastContext(
                    eye,
                    target,
                    RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE,
                    player);
            if (((ServerWorld) player.getEntityWorld()).raycast(ctx).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private static void forceMarker(ArmorStandEntity stand, boolean value) {
        try {
            Method m = ArmorStandEntity.class.getDeclaredMethod("setMarker", boolean.class);
            m.setAccessible(true);
            m.invoke(stand, value);
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to set armor stand marker flag: {}", e.getMessage());
        }
    }

    private static void forceSmall(ArmorStandEntity stand, boolean value) {
        try {
            Method m = ArmorStandEntity.class.getDeclaredMethod("setSmall", boolean.class);
            m.setAccessible(true);
            m.invoke(stand, value);
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to set armor stand small flag: {}", e.getMessage());
        }
    }

    private static boolean isFloodgateBedrock(ServerPlayerEntity player) {
        if (!floodgateChecked) {
            floodgateChecked = true;
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Method getInstance = apiClass.getMethod("getInstance");
                floodgateApiInstance = getInstance.invoke(null);
                floodgateCheckMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
                BedrockGravesAddon.LOGGER.info("[UniversalGraves-Geyser] Floodgate detected, enabling Bedrock overlays");
            } catch (Exception e) {
                BedrockGravesAddon.LOGGER.info("[UniversalGraves-Geyser] Floodgate not detected, Bedrock overlays disabled ({})",
                        e.toString());
            }
        }

        if (floodgateApiInstance == null || floodgateCheckMethod == null) {
            return false;
        }

        try {
            return (boolean) floodgateCheckMethod.invoke(floodgateApiInstance, player.getUuid());
        } catch (Exception e) {
            BedrockGravesAddon.LOGGER.debug("[UniversalGraves-Geyser] Failed to query Floodgate: {}", e.toString());
            return false;
        }
    }

    private static ItemStack pickItem(Grave grave) {
        for (var p : grave.getItems()) {
            if (!p.isEmpty() && !p.stack().isEmpty()) {
                return p.stack().copy();
            }
        }
        return ItemStack.EMPTY;
    }
}
