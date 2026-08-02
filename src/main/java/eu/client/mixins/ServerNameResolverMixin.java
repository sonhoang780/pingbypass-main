package eu.client.mixins;

import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Whatever resolver chain vanilla's ServerAddressResolver/Netty use internally keeps failing
 * with "Unknown host" for hosts with long DNS chains (multiple CNAMEs + many A records, e.g.
 * play.6b6t.org behind TCPShield) -- confirmed repeatedly that java.net.InetAddress resolves the
 * exact same host fine every time. Replace the resolve step outright with a plain
 * `new InetSocketAddress(host, port)` (java.net, proven reliable) instead of chasing which
 * specific internal resolver is broken.
 *
 * Critically, java.net.InetSocketAddress(String, int) keeps the ORIGINAL hostname string
 * alongside the resolved IP -- getHostString() returns it without re-resolving -- so the
 * ClientIntentionPacket still carries the real hostname (required for TCPShield-style
 * virtual-host routing) even though the connection itself dials the resolved IP directly.
 */
@Mixin(ServerNameResolver.class)
public class ServerNameResolverMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("EUClient/DnsFix");

    @Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
    private void resolveAddress(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
        try {
            InetSocketAddress resolved = new InetSocketAddress(address.getHost(), address.getPort());
            if (resolved.isUnresolved()) {
                LOGGER.warn("[PB] java.net fallback resolve of {} also unresolved", address.getHost());
                return;
            }
            LOGGER.info("[PB] Resolved {} via java.net fallback: {}", address.getHost(), resolved);
            cir.setReturnValue(Optional.of(ResolvedServerAddress.from(resolved)));
        } catch (Exception e) {
            LOGGER.warn("[PB] java.net fallback resolve of {} failed", address.getHost(), e);
        }
    }
}
