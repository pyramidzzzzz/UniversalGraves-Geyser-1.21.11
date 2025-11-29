package net.bedrockgraves.mixin;

import eu.pb4.graves.other.GraveUtils;
import net.bedrockgraves.BedrockGravesAddon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Run grave creation immediately instead of scheduling next tick, so items can't be lost on crash.
 */
@Mixin(GraveUtils.class)
public class GraveUtilsCreateGraveMixin {
    private static final int MAX_GRAVE_RETRY = 3;

    @Redirect(method = "createGrave", at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0))
    private static boolean bedrockgraves$runGraveCreationNow(List<Runnable> list, Object runnable) {
        if (runnable instanceof Runnable r) {
            runWithRetry(r, MAX_GRAVE_RETRY);
        }
        return true;
    }

    private static void runWithRetry(Runnable task, int remainingAttempts) {
        try {
            task.run();
        } catch (Throwable t) {
            if (remainingAttempts > 0) {
                BedrockGravesAddon.LOGGER.warn("Grave creation failed, retrying {} more time(s)", remainingAttempts, t);
                scheduleRetry(task, remainingAttempts - 1);
            } else {
                BedrockGravesAddon.LOGGER.error("Grave creation failed after retries", t);
            }
        }
    }

    private static void scheduleRetry(Runnable task, int remainingAttempts) {
        BedrockGravesAddon.DO_ON_NEXT_TICK.add(new Runnable() {
            @Override
            public void run() {
                runWithRetry(task, remainingAttempts);
            }
        });
    }
}
