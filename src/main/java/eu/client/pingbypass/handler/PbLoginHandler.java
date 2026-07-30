package eu.client.pingbypass.handler;

import com.mojang.authlib.GameProfile;
import eu.client.pingbypass.server.ProxyServer;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.util.CryptException;
import net.minecraft.util.Crypt;
import net.minecraft.network.protocol.login.ServerLoginPacketListener;
import net.minecraft.network.protocol.cookie.ServerboundCookieResponsePacket;
import net.minecraft.network.protocol.login.ServerboundLoginAcknowledgedPacket;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.ServerboundKeyPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.ClientboundLoginDisconnectPacket;
import net.minecraft.network.protocol.login.ClientboundHelloPacket;
import net.minecraft.network.protocol.login.ClientboundLoginFinishedPacket;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.chat.Component;
import net.minecraft.core.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Random;

/**
 * Handles the login state of the PingBypass proxy connection.
 * Performs RSA key exchange, enables encryption, sends LoginSuccess,
 * then transitions to PbPasswordHandler or play state.
 */
public class PbLoginHandler implements ServerLoginPacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(PbLoginHandler.class);
    private static final Random RANDOM = new Random();

    private final ProxyServer proxyServer;
    private final Connection connection;
    private final KeyPair keyPair;
    private final byte[] nonce = new byte[4];

    private LoginState state = LoginState.HELLO;
    private String playerName;

    public PbLoginHandler(ProxyServer proxyServer, Connection connection) {
        this.proxyServer = proxyServer;
        this.connection = connection;
        try {
            this.keyPair = Crypt.generateKeyPair();
        } catch (CryptException e) {
            throw new IllegalStateException("Failed to generate RSA keypair", e);
        }
        RANDOM.nextBytes(this.nonce);
    }

    @Override
    public void handleHello(ServerboundHelloPacket packet) {
        if (this.state != LoginState.HELLO) {
            throw new IllegalStateException("Unexpected hello packet");
        }

        this.playerName = packet.name();
        LOGGER.info("Login attempt from {}", this.playerName);

        this.state = LoginState.KEY;
        this.connection.send(new ClientboundHelloPacket(
                "",
                this.keyPair.getPublic().getEncoded(),
                this.nonce,
                false
        ));
    }

    @Override
    public void handleKey(ServerboundKeyPacket packet) {
        if (this.state != LoginState.KEY) {
            throw new IllegalStateException("Unexpected key packet");
        }

        PrivateKey privateKey = this.keyPair.getPrivate();

        if (!packet.isChallengeValid(this.nonce, privateKey)) {
            throw new IllegalStateException("Protocol error: invalid nonce!");
        }

        SecretKey secretKey;
        try {
            secretKey = packet.getSecretKey(privateKey);
        } catch (CryptException e) {
            throw new IllegalStateException("Protocol error", e);
        }
        this.state = LoginState.READY_TO_ACCEPT;

        try {
            Cipher decryptCipher = Crypt.getCipher(Cipher.DECRYPT_MODE, secretKey);
            Cipher encryptCipher = Crypt.getCipher(Cipher.ENCRYPT_MODE, secretKey);
            this.connection.setEncryptionKey(decryptCipher, encryptCipher);
        } catch (CryptException e) {
            throw new IllegalStateException("Protocol error", e);
        }

        acceptPlayer();
    }

    private GameProfile acceptedProfile;

    private void acceptPlayer() {
        GameProfile profile = new GameProfile(
                UUIDUtil.createOfflinePlayerUUID(this.playerName),
                this.playerName
        );

        this.acceptedProfile = profile;
        this.state = LoginState.PROTOCOL_SWITCHING;
        this.connection.send(new ClientboundLoginFinishedPacket(profile));
        LOGGER.info("Login success for {} ({}), waiting for LOGIN_ACKNOWLEDGED", profile.name(), profile.id());
    }

    @Override
    public void handleLoginAcknowledgement(ServerboundLoginAcknowledgedPacket packet) {
        if (this.state != LoginState.PROTOCOL_SWITCHING) {
            throw new IllegalStateException("Unexpected login acknowledgement packet");
        }

        // Client has acknowledged login — now transition to CONFIGURATION phase.
        // The PbConfigurationHandler will perform a minimal config handshake
        // (send FINISH_CONFIGURATION, wait for client ack) then transition to PLAY.
        this.connection.setupOutboundProtocol(ConfigurationProtocols.CLIENTBOUND);
        this.connection.setupInboundProtocol(
                ConfigurationProtocols.SERVERBOUND,
                new PbConfigurationHandler(this.proxyServer, this.connection, this.acceptedProfile)
        );
        this.state = LoginState.ACCEPTED;
        LOGGER.info("Transitioning {} to configuration phase", this.playerName);
    }

    @Override
    public void handleCustomQueryPacket(ServerboundCustomQueryAnswerPacket packet) {
        this.disconnect(Component.literal("Unexpected query response"));
    }

    @Override
    public void handleCookieResponse(ServerboundCookieResponsePacket packet) {
        this.disconnect(Component.literal("Unexpected cookie response"));
    }

    @Override
    public void onDisconnect(DisconnectionDetails info) {
        LOGGER.info("Client {} disconnected during login: {}",
                this.playerName != null ? this.playerName : "unknown",
                info.reason());
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
    }

    private void disconnect(Component reason) {
        try {
            this.connection.send(new ClientboundLoginDisconnectPacket(reason));
            this.connection.disconnect(reason);
        } catch (Exception e) {
            LOGGER.error("Error disconnecting client", e);
        }
    }

    public String getPlayerName() {
        return playerName;
    }

    private enum LoginState {
        HELLO,
        KEY,
        READY_TO_ACCEPT,
        PROTOCOL_SWITCHING,
        ACCEPTED
    }
}
