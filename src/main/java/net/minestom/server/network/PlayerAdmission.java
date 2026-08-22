package net.minestom.server.network;

import net.minestom.server.entity.Player;
import net.minestom.server.network.player.GameProfile;
import org.jetbrains.annotations.ApiStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Adapts a protocol's login sequence to the shared player admission lifecycle.
 *
 * <p>The admission lifecycle first supplies the final profile, creates and registers the player,
 * then supplies the created player for protocol-specific preparation. Implementations must not
 * create, register, remove, or transition the player themselves.
 */
@ApiStatus.Experimental
public interface PlayerAdmission {

    /**
     * Requests atomic name and UUID uniqueness for this admission.
     *
     * <p>The shared admission lifecycle checks both the candidate and final pre-login profile
     * against online players and other uniqueness-constrained admissions. Protocols that retain
     * their existing duplicate-login behavior can leave this disabled.
     *
     * @return whether this admission requires a unique identity
     */
    default boolean requiresUniqueIdentity() {
        return false;
    }

    /**
     * Accepts the final profile produced by {@link net.minestom.server.event.player.AsyncPlayerPreLoginEvent}.
     *
     * @param gameProfile the final profile
     * @return a future completed once the protocol is ready for player creation
     */
    CompletableFuture<Void> accept(GameProfile gameProfile);

    /**
     * Prepares the created player for entering the shared play lifecycle.
     *
     * @param player the created and registered player
     * @return a future completed once protocol-specific preparation is complete
     */
    CompletableFuture<Void> prepare(Player player);
}
