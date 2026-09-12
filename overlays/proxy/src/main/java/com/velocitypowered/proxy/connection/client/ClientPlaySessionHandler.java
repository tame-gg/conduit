/*
 * Copyright (C) 2018-2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.connection.client;

import static com.velocitypowered.proxy.protocol.util.PluginMessageUtil.constructChannelsPacket;

import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.suggestion.Suggestion;
import com.velocityctd.api.event.player.TabCompleteRequestEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.CookieReceiveEvent;
import com.velocitypowered.api.event.player.PlayerChannelRegisterEvent;
import com.velocitypowered.api.event.player.PlayerChannelUnregisterEvent;
import com.velocitypowered.api.event.player.PlayerClientBrandEvent;
import com.velocitypowered.api.event.player.TabCompleteEvent;
import com.velocitypowered.api.event.player.configuration.PlayerEnteredConfigurationEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.player.TabListEntry;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.conduit.Conduit;
import com.velocitypowered.proxy.conduit.ConduitConfig;
import com.velocitypowered.proxy.conduit.network.TabCompleteCache;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.backend.BackendConnectionPhases;
import com.velocitypowered.proxy.connection.backend.BungeeCordMessageResponder;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.forge.legacy.LegacyForgeConstants;
import com.velocitypowered.proxy.connection.player.resourcepack.ResourcePackResponseBundle;
import com.velocitypowered.proxy.connection.registry.DimensionInfo;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.StateRegistry;
import com.velocitypowered.proxy.protocol.netty.MinecraftDecoder;
import com.velocitypowered.proxy.protocol.packet.BossBarPacket;
import com.velocitypowered.proxy.protocol.packet.ClientSettingsPacket;
import com.velocitypowered.proxy.protocol.packet.ClientboundSoundEntityPacket;
import com.velocitypowered.proxy.protocol.packet.EntityEventPacket;
import com.velocitypowered.proxy.protocol.packet.EntityMetadataPacket;
import com.velocitypowered.proxy.protocol.packet.EntityVelocityPacket;
import com.velocitypowered.proxy.protocol.packet.GameEventPacket;
import com.velocitypowered.proxy.protocol.packet.HeaderAndFooterPacket;
import com.velocitypowered.proxy.protocol.packet.JoinGamePacket;
import com.velocitypowered.proxy.protocol.packet.KeepAlivePacket;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import com.velocitypowered.proxy.protocol.packet.RemoveEntitiesPacket;
import com.velocitypowered.proxy.protocol.packet.RemoveEntityEffectPacket;
import com.velocitypowered.proxy.protocol.packet.ResourcePackResponsePacket;
import com.velocitypowered.proxy.protocol.packet.RespawnPacket;
import com.velocitypowered.proxy.protocol.packet.ScoreboardObjectivePacket;
import com.velocitypowered.proxy.protocol.packet.ScoreboardTeamPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundPlayerLoadedPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteRequestPacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket;
import com.velocitypowered.proxy.protocol.packet.TabCompleteResponsePacket.Offer;
import com.velocitypowered.proxy.protocol.packet.UpdateAttributesPacket;
import com.velocitypowered.proxy.protocol.packet.UpdateAttributesPacket.AttributeSnapshot;
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.ChatTimeKeeper;
import com.velocitypowered.proxy.protocol.packet.chat.CommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.keyed.KeyedPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.legacy.LegacyCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionCommandHandler;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerChatPacket;
import com.velocitypowered.proxy.protocol.packet.chat.session.SessionPlayerCommandPacket;
import com.velocitypowered.proxy.protocol.packet.config.FinishedUpdatePacket;
import com.velocitypowered.proxy.protocol.packet.title.GenericTitlePacket;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import com.velocitypowered.proxy.util.CharacterUtil;
import com.velocitypowered.proxy.util.except.QuietRuntimeException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.format.NamedTextColor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Handles communication with the connected Minecraft client. This is effectively the primary nerve
 * center that joins backend servers with players.
 */
public class ClientPlaySessionHandler implements MinecraftSessionHandler {
  private static final boolean BACKPRESSURE_LOG =
      Boolean.getBoolean("velocity.log-server-backpressure");

  // Caps the per-connection queue used while the FML/login phases are not yet "complete". Without
  // these caps, a client that never completes its handshake phase can spam plugin messages (each up
  // to ~32 KiB serverbound) and grow the queue without bound.
  private static final long MAX_QUEUED_LOGIN_PLUGIN_MESSAGE_BYTES =
      Long.getLong("velocity.max-queued-login-plugin-message-bytes", 4L * 1024 * 1024);

  private static final int MAX_QUEUED_LOGIN_PLUGIN_MESSAGES =
      Integer.getInteger("velocity.max-queued-login-plugin-messages", 1024);

  private static final Logger LOGGER = LogManager.getLogger(ClientPlaySessionHandler.class);

  // Number of status effects present in every client that can switch seamlessly (1.20.2+): the
  // vanilla effects from speed up to and including darkness.
  private static final int VANILLA_EFFECT_COUNT = 33;

  private final ConnectedPlayer player;

  private boolean spawned = false;

  private final Set<UUID> serverBossBars = ConcurrentHashMap.newKeySet();

  private final Queue<PluginMessagePacket> loginPluginMessages = new ConcurrentLinkedQueue<>();

  private final AtomicLong loginPluginMessagesBytes = new AtomicLong();

  private final AtomicInteger loginPluginMessagesCount = new AtomicInteger();

  private volatile boolean loginPluginMessagesOverflowed;

  private final VelocityServer server;

  private @Nullable TabCompleteRequestPacket outstandingTabComplete;

  private final ChatHandler<? extends MinecraftPacket> chatHandler;

  private final CommandHandler<? extends MinecraftPacket> commandHandler;

  private final ChatTimeKeeper timeKeeper = new ChatTimeKeeper();

  private CompletableFuture<Void> configSwitchFuture;

  private int failedTabCompleteAttempts;

  private final Set<Integer> trackedEntityIds = ConcurrentHashMap.newKeySet();
  private final Set<String> trackedScoreboardObjectives = ConcurrentHashMap.newKeySet();
  private final Set<String> trackedScoreboardTeams = ConcurrentHashMap.newKeySet();
  private final Set<Integer> trackedPlayerEffects = ConcurrentHashMap.newKeySet();
  private final Map<String, AttributeSnapshot> trackedPlayerAttributes =
      new ConcurrentHashMap<>();
  private @Nullable String currentDimension;
  private @Nullable Integer clientEntityId;
  private boolean seamlessPlayActive;

  /**
   * Constructs a client play session handler.
   *
   * @param server the Velocity server instance
   * @param player the player
   */
  public ClientPlaySessionHandler(VelocityServer server, ConnectedPlayer player) {
    this.player = player;
    this.server = server;

    if (this.player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      this.chatHandler = new SessionChatHandler(this.player, this.server);
      this.commandHandler = new SessionCommandHandler(this.player, this.server);
    } else if (this.player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19)) {
      this.chatHandler = new KeyedChatHandler(this.server, this.player);
      this.commandHandler = new KeyedCommandHandler(this.player, this.server);
    } else {
      this.chatHandler = new LegacyChatHandler(this.server, this.player);
      this.commandHandler = new LegacyCommandHandler(this.player, this.server);
    }
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean updateTimeKeeper(@Nullable Instant instant) {
    if (instant == null) {
      return true;
    }

    if (!this.timeKeeper.update(instant)) {
      player.disconnect(Component.translatable("multiplayer.disconnect.out_of_order_chat"));
      return false;
    }

    return true;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean validateChat(String message) {
    if (!server.getConfiguration().isAllowIllegalCharactersInChat() && CharacterUtil.containsIllegalCharacters(message)) {
      player.disconnect(Component.translatable("velocity.error.illegal-chat-characters", NamedTextColor.RED));
      return false;
    }

    return true;
  }

  @Override
  public void activated() {
    configSwitchFuture = new CompletableFuture<>();
    Collection<ChannelIdentifier> channels = server.getChannelRegistrar().getChannelsForProtocol(player.getProtocolVersion());
    if (!channels.isEmpty()) {
      PluginMessagePacket register = constructChannelsPacket(player.getProtocolVersion(), channels);
      player.getConnection().write(register);
    }
  }

  @Override
  public void deactivated() {
    player.discardChatQueue();
    PluginMessagePacket message;
    while ((message = loginPluginMessages.poll()) != null) {
      ReferenceCountUtil.release(message);
    }

    loginPluginMessagesBytes.set(0);
    loginPluginMessagesCount.set(0);
  }

  /**
   * Adds a retained plugin message to the queue used while the FML/login phases are still in
   * progress, enforcing the per-connection byte and count caps. Returns {@code true} if queued,
   * {@code false} if the packet was released (and the player disconnected on overflow).
   */
  private boolean enqueueLoginPluginMessage(PluginMessagePacket packet) {
    if (loginPluginMessagesOverflowed) {
      ReferenceCountUtil.release(packet);
      return false;
    }

    int packetSize = packet.content().readableBytes();
    long newBytes = loginPluginMessagesBytes.addAndGet(packetSize);
    int newCount = loginPluginMessagesCount.incrementAndGet();
    if (newBytes > MAX_QUEUED_LOGIN_PLUGIN_MESSAGE_BYTES
        || newCount > MAX_QUEUED_LOGIN_PLUGIN_MESSAGES) {
      loginPluginMessagesOverflowed = true;
      ReferenceCountUtil.release(packet);
      LOGGER.warn("Disconnecting {}: pre-join plugin-message queue exceeded its limits "
              + "({} messages, {} bytes).", player, newCount, newBytes);
      player.disconnect(Component.translatable("velocity.error.plugin-message-overflow"));
      return false;
    }

    loginPluginMessages.add(packet);
    return true;
  }

  @Override
  public boolean handle(KeepAlivePacket packet) {
    player.forwardKeepAlive(packet);
    return true;
  }

  @Override
  public boolean handle(ClientSettingsPacket packet) {
    player.setClientSettings(packet);
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection == null) {
      // No server connection yet, probably transitioning.
      return true;
    }

    player.getConnectedServer().ensureConnected().write(packet);
    return true; // will forward onto the server
  }

  @Override
  public boolean handle(SessionPlayerCommandPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimeStamp())) {
      return true;
    }

    if (!validateChat(packet.getCommand())) {
      return true;
    }

    return this.commandHandler.handlePlayerCommand(packet);
  }

  @Override
  public boolean handle(SessionPlayerChatPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimestamp())) {
      return true;
    }

    if (!validateChat(packet.getMessage())) {
      return true;
    }

    return this.chatHandler.handlePlayerChat(packet);
  }

  @Override
  public boolean handle(KeyedPlayerCommandPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getTimestamp())) {
      return true;
    }

    if (!validateChat(packet.getCommand())) {
      return true;
    }

    return this.commandHandler.handlePlayerCommand(packet);
  }

  @Override
  public boolean handle(KeyedPlayerChatPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    if (!updateTimeKeeper(packet.getExpiry())) {
      return true;
    }

    if (!validateChat(packet.getMessage())) {
      return true;
    }

    return this.chatHandler.handlePlayerChat(packet);
  }

  @Override
  public boolean handle(LegacyChatPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    String msg = packet.getMessage();
    if (!validateChat(msg)) {
      return true;
    }

    if (msg.startsWith("/")) {
      this.commandHandler.handlePlayerCommand(packet);
    } else {
      this.chatHandler.handlePlayerChat(packet);
    }

    return true;
  }

  @Override
  public boolean handle(TabCompleteRequestPacket packet) {
    boolean isCommand = !packet.isAssumeCommand() && packet.getCommand().startsWith("/");

    if (isCommand) {
      return this.handleCommandTabComplete(packet);
    } else {
      return this.handleRegularTabComplete(packet);
    }
  }

  @Override
  public boolean handle(PluginMessagePacket packet) {
    // Handling an edge case, when a packet with FML client handshake (state COMPLETE)
    // arrives after JoinGame packet from destination server
    VelocityServerConnection serverConn =
        (player.getConnectedServer() == null
            && packet.getChannel().equals(
            LegacyForgeConstants.FORGE_LEGACY_HANDSHAKE_CHANNEL))
            ? player.getConnectionInFlight() : player.getConnectedServer();

    MinecraftConnection backendConn = serverConn != null ? serverConn.getConnection() : null;
    if (serverConn != null && backendConn != null) {
      if (backendConn.getState() != StateRegistry.PLAY) {
        LOGGER.warn("A plugin message was received while the backend server was not "
            + "ready. Channel: {}. Packet discarded.", packet.getChannel());
      } else if (PluginMessageUtil.isRegister(packet)) {
        List<ChannelIdentifier> channels = PluginMessageUtil.getChannels(this.player.getClientsideChannels().size(), packet,
                this.player.getProtocolVersion(), this.server);
        player.getClientsideChannels().addAll(channels);
        server.getEventManager().fireAndForget(new PlayerChannelRegisterEvent(player, ImmutableList.copyOf(channels)));
        backendConn.write(packet.retain());
      } else if (PluginMessageUtil.isUnregister(packet)) {
        List<ChannelIdentifier> channels =
            PluginMessageUtil.getChannels(0, packet, this.player.getProtocolVersion(), this.server);
        player.getClientsideChannels().removeAll(channels);
        server.getEventManager()
            .fireAndForget(
                new PlayerChannelUnregisterEvent(player, ImmutableList.copyOf(channels)));
        backendConn.write(packet.retain());
      } else if (PluginMessageUtil.isMcBrand(packet)) {
        String brand = PluginMessageUtil.readBrandMessage(packet.content());
        server.getEventManager().fireAndForget(new PlayerClientBrandEvent(player, brand));
        player.setClientBrand(brand);
        backendConn.write(packet.retain());
      } else if (BungeeCordMessageResponder.isBungeeCordMessage(packet)) {
        return true;
      } else {
        if (serverConn.getPhase() == BackendConnectionPhases.IN_TRANSITION) {
          // We must bypass the currently connected server when forwarding Forge packets.
          VelocityServerConnection inFlight = player.getConnectionInFlight();
          if (inFlight != null) {
            player.getPhase().handle(player, packet, inFlight);
          }

          return true;
        }

        if (!player.getPhase().handle(player, packet, serverConn)) {
          ChannelIdentifier id = server.getChannelRegistrar().getFromId(packet.getChannel());
          if (id == null) {
            // We don't have any plugins listening on this channel, process the packet now.
            if (!player.getPhase().consideredComplete() || !serverConn.getPhase().consideredComplete()) {
              // The client is trying to send messages too early. This is primarily caused by mods,
              // but further aggravated by Velocity. To work around these issues, we will queue any
              // non-FML handshake messages to be sent once the FML handshake has completed or the
              // JoinGame packet has been received by "the" proxy, whichever comes first.
              //
              // We also need to make sure to retain these packets, so they can be flushed
              // appropriately.
              enqueueLoginPluginMessage(packet.retain());
            } else {
              // The connection is ready, send the packet now.
              backendConn.write(packet.retain());
            }
          } else {
            byte[] copy = ByteBufUtil.getBytes(packet.content());
            PluginMessageEvent event = new PluginMessageEvent(player, serverConn, id, copy);
            server.getEventManager().fire(event).thenAcceptAsync(pme -> {
              if (pme.getResult().isAllowed()) {
                PluginMessagePacket message = new PluginMessagePacket(packet.getChannel(),
                    Unpooled.wrappedBuffer(copy));
                if (!player.getPhase().consideredComplete() || !serverConn.getPhase()
                    .consideredComplete()) {
                  // We're still processing the connection (see above), enqueue the packet for now.
                  enqueueLoginPluginMessage(message.retain());
                } else {
                  backendConn.write(message);
                }
              }
            }, backendConn.eventLoop()).exceptionally((ex) -> {
              LOGGER.error("Exception while handling plugin message packet for {}", player, ex);
              return null;
            });
          }
        }
      }
    }

    return true;
  }

  @Override
  public boolean handle(ResourcePackResponsePacket packet) {
    return player.resourcePackHandler().onResourcePackResponse(
        new ResourcePackResponseBundle(packet.getId(),
            packet.getHash(),
            packet.getStatus()));
  }

  @Override
  public boolean handle(FinishedUpdatePacket packet) {
    if (!player.getConnection().pendingConfigurationSwitch) {
      throw new QuietRuntimeException("Not expecting reconfiguration");
    }

    // Complete client switch
    player.getConnection().setActiveSessionHandler(StateRegistry.CONFIG);
    VelocityServerConnection serverConnection = player.getConnectedServer();
    server.getEventManager().fireAndForget(new PlayerEnteredConfigurationEvent(player, serverConnection));
    if (serverConnection != null) {
      MinecraftConnection smc = serverConnection.ensureConnected();
      CompletableFuture.runAsync(() -> {
        smc.write(packet);
        smc.setActiveSessionHandler(StateRegistry.CONFIG);
        smc.setAutoReading(true);
      }, smc.eventLoop()).exceptionally((ex) -> {
        LOGGER.error("Error forwarding config state acknowledgement to server:", ex);
        return null;
      });
    }

    configSwitchFuture.complete(null);
    return true;
  }

  @Override
  public boolean handle(ChatAcknowledgementPacket packet) {
    if (player.getCurrentServer().isEmpty()) {
      return true;
    }

    player.getChatQueue().handleAcknowledgement(packet.offset());
    return true;
  }

  @Override
  public boolean handle(ServerboundCookieResponsePacket packet) {
    server.getEventManager()
        .fire(new CookieReceiveEvent(player, packet.getKey(), packet.getPayload()))
        .thenAcceptAsync(event -> {
          if (event.getResult().isAllowed()) {
            VelocityServerConnection serverConnection = player.getConnectedServer();
            if (serverConnection != null) {
              Key resultedKey = event.getResult().getKey() == null
                  ? event.getOriginalKey() : event.getResult().getKey();
              byte[] resultedData = event.getResult().getData() == null
                  ? event.getOriginalData() : event.getResult().getData();

              serverConnection.ensureConnected().write(new ServerboundCookieResponsePacket(resultedKey, resultedData));
            }
          }
        }, player.getConnection().eventLoop());

    return true;
  }

  @Override
  public boolean handle(JoinGamePacket packet) {
    // Forward the packet as normal, but discard any chat state we have queued - the client will do this too
    player.discardChatQueue();
    return false;
  }

  @Override
  public void handleGeneric(MinecraftPacket packet) {
    VelocityServerConnection serverConnection = player.getConnectionInFlightOrConnectedServer();
    if (serverConnection == null) {
      return;
    }

    MinecraftConnection smc = serverConnection.getConnection();
    boolean stateAllowsForward = smc != null
        && !smc.isClosed()
        && serverConnection.getPhase().consideredComplete()
        && smc.getState() == StateRegistry.PLAY;
    if (stateAllowsForward) {
      if (packet instanceof PluginMessagePacket) {
        ((PluginMessagePacket) packet).retain();
      }
      smc.write(packet);
    }
  }

  @Override
  public void handleUnknown(ByteBuf buf) {
    VelocityServerConnection serverConnection = player.getConnectionInFlightOrConnectedServer();
    if (serverConnection == null) {
      return;
    }

    MinecraftConnection smc = serverConnection.getConnection();
    boolean stateAllowsForward = smc != null
        && !smc.isClosed()
        && serverConnection.getPhase().consideredComplete()
        && smc.getState() == StateRegistry.PLAY;
    if (stateAllowsForward) {
      smc.write(buf.retain());
    }
  }

  @Override
  public void disconnected() {
    // Conduit: tab-complete entries are per player, so they die with the player.
    Conduit.get().getTabCompleteCache().invalidatePlayer(player.getUniqueId());
    player.teardown();
  }

  @Override
  public void exception(Throwable throwable) {
    player.disconnect(Component.translatable("velocity.error.player-connection-error", NamedTextColor.RED));
    if (MinecraftDecoder.DEBUG) {
      LOGGER.info("Exception while handling packet for {}", player, throwable);
    }
  }

  @Override
  public void writabilityChanged() {
    boolean writable = player.getConnection().getChannel().isWritable();

    if (BACKPRESSURE_LOG) {
      if (writable) {
        LOGGER.info("{} is writable, will auto-read backend connection data", player);
      } else {
        LOGGER.info("{} is not writable, not auto-reading backend connection data", player);
      }
    }

    if (!writable) {
      // We might have packets queued from the server, so flush them now to free up memory. Make
      // sure to do it on a future invocation of the event loop, otherwise while the issue will
      // fix itself, we'll still disable auto-reading, and instead of backpressure resolution, we
      // get client timeouts.
      player.getConnection().eventLoop().execute(() -> player.getConnection().flush());
    }

    VelocityServerConnection serverConn = player.getConnectedServer();
    if (serverConn != null) {
      MinecraftConnection smc = serverConn.getConnection();
      if (smc != null) {
        smc.setAutoReading(writable);
      }
    }
  }

  /**
   * Handles switching stages for swapping between servers.
   *
   * @return a future that completes when the switch is complete
   */
  public CompletableFuture<Void> doSwitch() {
    VelocityServerConnection existingConnection = player.getConnectedServer();

    if (existingConnection != null) {
      // Shut down the existing server connection.
      player.setConnectedServer(null);
      existingConnection.disconnect();

      // Send keep alive to try to avoid timeouts
      player.sendKeepAlive();

      // Config state clears everything in the client. No need to clear later.
      spawned = false;
      this.seamlessPlayActive = false;
      player.clearPlayerListHeaderAndFooterSilent();
      player.getTabList().clearAllSilent();
      if (player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
        player.getBossBarManager().dropPackets();
      } else {
        serverBossBars.clear();
      }
    }

    player.switchToConfigState();

    return configSwitchFuture;
  }

  /**
   * Handles the {@code JoinGame} packet. This function is responsible for handling the client-side
   * switching servers in Velocity.
   *
   * @param joinGame    the join game packet
   * @param destination the new server we are connecting to
   */
  public void handleBackendJoinGame(JoinGamePacket joinGame, VelocityServerConnection destination) {
    final MinecraftConnection serverMc = destination.ensureConnected();
    final boolean playStay = spawned && isPlayStaySwitch(destination);
    final boolean seamless = playStay && currentDimension != null
        && currentDimension.equals(dimensionKey(joinGame));

    if (!spawned) {
      // The player wasn't spawned in yet, so we don't need to do anything special.
      // Send JoinGame.
      spawned = true;
      this.seamlessPlayActive = false;
      this.clientEntityId = joinGame.getEntityId();
      player.getConnection().delayedWrite(joinGame);
      // Required for Legacy Forge
      player.getPhase().onFirstJoin(player);
    } else if (seamless) {
      this.seamlessPlayActive = true;
      this.doSeamlessPlaySwitch(joinGame);
    } else if (playStay) {
      // Client stayed in Play (backend config was absorbed) but the destination is a different
      // dimension, e.g. Overworld hub → End limbo. Vanilla never ran the config-phase bar clear,
      // so strip leftover HUD and then send Join Game + Respawn for the new dimension.
      this.seamlessPlayActive = false;
      this.stripPreviousServerHud();
      player.getTabList().clearAll();
      if (player.getConnection().getType() == ConnectionTypes.LEGACY_FORGE) {
        this.doSafeClientServerSwitch(joinGame);
      } else {
        this.doFastClientServerSwitch(joinGame);
      }
    } else {
      this.seamlessPlayActive = false;
      // Clear tab list to avoid duplicate entries
      player.getTabList().clearAll();

      // The player is switching from a server already, so we need to tell the client to change
      // entity IDs and send new dimension information.
      if (player.getConnection().getType() == ConnectionTypes.LEGACY_FORGE) {
        this.doSafeClientServerSwitch(joinGame);
      } else {
        this.doFastClientServerSwitch(joinGame);
      }
    }

    if (!seamless) {
      trackedEntityIds.clear();
      trackedScoreboardObjectives.clear();
      trackedScoreboardTeams.clear();
      trackedPlayerEffects.clear();
      trackedPlayerAttributes.clear();
      serverBossBars.clear();
      this.clientEntityId = joinGame.getEntityId();
    }
    this.currentDimension = dimensionKey(joinGame);

    destination.setEntityId(joinGame.getEntityId()); // Sound API function

    if (player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      if (!seamless) {
        player.getBossBarManager().sendBossBars();
      }
    } else {
      // Remove previous boss bars. These don't get cleared when sending JoinGame (up until 1.20.2),
      // thus the need to track them.
      writeBossBarRemoves(serverBossBars);
      serverBossBars.clear();
    }

    if (playStay && player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_21_4)) {
      serverMc.delayedWrite(ServerboundPlayerLoadedPacket.INSTANCE);
    }

    // Tell the server about the proxy's plugin message channels.
    ProtocolVersion serverVersion = serverMc.getProtocolVersion();
    Collection<ChannelIdentifier> channels = server.getChannelRegistrar().getChannelsForProtocol(serverMc.getProtocolVersion());
    if (!channels.isEmpty()) {
      serverMc.delayedWrite(constructChannelsPacket(serverVersion, channels));
    }

    // Tell the server about this client's plugin message channels.
    if (!player.getClientsideChannels().isEmpty()) {
      serverMc.delayedWrite(constructChannelsPacket(serverVersion, player.getClientsideChannels()));
    }

    // If we had plugin messages queued during login/FML handshake, send them now.
    PluginMessagePacket pm;
    while ((pm = loginPluginMessages.poll()) != null) {
      serverMc.delayedWrite(pm);
    }

    loginPluginMessagesBytes.set(0);
    loginPluginMessagesCount.set(0);

    // Clear any title from the previous server.
    if (!seamless && player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
      player.getConnection().delayedWrite(
          GenericTitlePacket.constructTitlePacket(GenericTitlePacket.ActionType.RESET,
              player.getProtocolVersion()));
    }

    // Flush everything
    player.getConnection().flush();
    serverMc.flush();
    destination.completeJoin();

    // Conduit: when the client stayed in the PLAY state (a seamless switch), soften the otherwise
    // instantaneous transition and prevent the "stuck until reconnect" desync by briefly holding
    // the player's own input while the destination backend streams them in, and optionally play a
    // teleport cue.
    if (playStay) {
      applySeamlessSwitchEffects();
    }
  }

  /**
   * Applies the configurable settle delay and teleport sound to a seamless server switch.
   *
   * <p>The settle delay pauses reading of the client's inbound packets for a short window right
   * after the switch. Movement the player makes before the destination server has finished loading
   * them in is buffered instead of being processed against a not-yet-ready world, which avoids the
   * player becoming "stuck" and having to reconnect. It also stops the switch from feeling
   * jarringly instantaneous.
   */
  private void applySeamlessSwitchEffects() {
    final ConduitConfig config = Conduit.get().getConfig();

    // Play the teleport cue by attaching the sound to the player's own (client-visible) entity.
    // We deliberately do NOT use player.playSound(): at this point in the switch the player's
    // connected server has not been reassigned yet (TransitionSessionHandler sets it only after
    // handleBackendJoinGame returns), so Velocity would emit the sound against the previous
    // server's entity id and the client would hear nothing.
    if (config.isSeamlessSwitchSoundEnabled() && clientEntityId != null
        && player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19_3)) {
      try {
        final Sound sound = Sound.sound(
            Key.key(config.getSeamlessSwitchSound()),
            Sound.Source.PLAYER,
            config.getSeamlessSwitchSoundVolume(),
            config.getSeamlessSwitchSoundPitch());
        player.getConnection().write(
            new ClientboundSoundEntityPacket(sound, null, clientEntityId));
      } catch (RuntimeException e) {
        // An invalid sound key must never break the actual server switch.
        LOGGER.warn("Invalid seamless-switch-sound '{}', skipping switch sound: {}",
            config.getSeamlessSwitchSound(), e.getMessage());
      }
    }

    final int settleMs = config.getSeamlessSwitchSettleMs();
    if (settleMs > 0) {
      final MinecraftConnection clientConn = player.getConnection();
      clientConn.setAutoReading(false);
      clientConn.eventLoop().schedule(() -> {
        if (!clientConn.isClosed()) {
          clientConn.setAutoReading(true);
        }
      }, settleMs, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Returns whether the client stayed in Play while the backend ran configuration, so leftover
   * HUD must be stripped even when the destination is a different dimension.
   */
  public boolean isPlayStaySwitch(VelocityServerConnection destination) {
    if (!Conduit.get().getConfig().isSeamlessServerSwitches()) {
      return false;
    }
    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_20_2)) {
      return false;
    }
    if (player.getConnection().getType() == ConnectionTypes.LEGACY_FORGE) {
      return false;
    }
    return player.getConnectionInFlight() == destination;
  }

  /**
   * Returns whether this Join Game can skip the client configuration/respawn sequence.
   */
  public boolean canDoSeamlessPlaySwitch(JoinGamePacket joinGame, VelocityServerConnection destination) {
    if (!isPlayStaySwitch(destination)) {
      return false;
    }
    return currentDimension != null && currentDimension.equals(dimensionKey(joinGame));
  }

  /**
   * Returns a value identifying the dimension a Join Game or Respawn packet places the player in.
   *
   * <p>Which field actually carries the dimension depends on the protocol: 1.20.5 and newer send a
   * numeric dimension type id, while 1.20.2 to 1.20.4 send the registry identifier as a string and
   * leave the numeric field at zero. Comparing only the numeric field made every 1.20.2-1.20.4
   * switch look like a same-dimension move, so the client was never told about a dimension change
   * and kept the previous world's sky, lighting, and weather.
   */
  private static String dimensionKey(int dimension, @Nullable DimensionInfo dimensionInfo) {
    final String identifier = dimensionInfo == null ? "" : dimensionInfo.getRegistryIdentifier();
    return identifier.isEmpty() ? Integer.toString(dimension) : identifier;
  }

  private static String dimensionKey(JoinGamePacket joinGame) {
    return dimensionKey(joinGame.getDimension(), joinGame.getDimensionInfo());
  }

  private void doSeamlessPlaySwitch(JoinGamePacket joinGame) {
    stripPreviousServerHud();
    player.getConnection().delayedWrite(GameEventPacket.changeGamemode(joinGame.getGamemode()));
    player.getConnection().delayedWrite(
        new GameEventPacket(GameEventPacket.EVENT_LIMITED_CRAFTING,
            joinGame.getDoLimitedCrafting() ? 1.0f : 0.0f));
    clearWeather();
  }

  /**
   * Returns the client to clear weather so the previous server's rain or thunderstorm does not
   * follow the player.
   *
   * <p>A destination server only announces weather when it actually has some, so nothing would undo
   * the previous server's storm. Ending the rain on its own is not enough either: the client stores
   * the rain and thunder gradients separately and ending the rain sets the rain gradient to full,
   * expecting the server to fade it out with subsequent level updates. Both gradients are therefore
   * zeroed here as well. A destination that is raining sends its own weather state right after the
   * switch, which overrides this.
   */
  private void clearWeather() {
    player.getConnection().delayedWrite(
        new GameEventPacket(GameEventPacket.EVENT_END_RAINING, 0.0f));
    player.getConnection().delayedWrite(
        new GameEventPacket(GameEventPacket.EVENT_RAIN_LEVEL_CHANGE, 0.0f));
    player.getConnection().delayedWrite(
        new GameEventPacket(GameEventPacket.EVENT_THUNDER_LEVEL_CHANGE, 0.0f));
  }

  private void stripPreviousServerHud() {
    for (TabListEntry entry : player.getTabList().getEntries()) {
      final UUID uuid = entry.getProfile().getId();
      if (!uuid.equals(player.getUniqueId())) {
        player.getTabList().removeEntry(uuid);
      }
    }
    if (!trackedEntityIds.isEmpty()) {
      player.getConnection().delayedWrite(new RemoveEntitiesPacket(new ArrayList<>(trackedEntityIds)));
      trackedEntityIds.clear();
    }
    if (!trackedScoreboardTeams.isEmpty()) {
      for (String team : trackedScoreboardTeams) {
        player.getConnection().delayedWrite(ScoreboardTeamPacket.remove(team));
      }
      trackedScoreboardTeams.clear();
    }
    if (!trackedScoreboardObjectives.isEmpty()) {
      for (String objective : trackedScoreboardObjectives) {
        player.getConnection().delayedWrite(ScoreboardObjectivePacket.remove(objective));
      }
      trackedScoreboardObjectives.clear();
    }
    clearServerBossBars();
    if (clientEntityId != null) {
      player.getConnection().delayedWrite(EntityEventPacket.clearOperator(clientEntityId));
      player.getConnection().delayedWrite(
          EntityMetadataPacket.resetPlayerState(clientEntityId, player.getProtocolVersion()));
      if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_21_2)) {
        // From 1.21.2 the destination's spawn teleport carries the velocity and resets it itself.
        player.getConnection().delayedWrite(EntityVelocityPacket.stop(clientEntityId));
      }
    }
    clearPlayerEffects();
    resetPlayerAttributes();
    if (player.getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_8)) {
      player.getConnection().delayedWrite(HeaderAndFooterPacket.reset(player.getProtocolVersion()));
      player.clearPlayerListHeaderAndFooterSilent();
      // Clear any lingering title/subtitle/action bar from the previous server. The non-seamless
      // switch path resets the title after Join Game, but a seamless (client-stays-in-Play) switch
      // never runs that reset, so a title such as a hub's "<server> server" banner would otherwise
      // stick across the switch.
      player.getConnection().delayedWrite(
          GenericTitlePacket.constructTitlePacket(GenericTitlePacket.ActionType.RESET,
              player.getProtocolVersion()));
    }
  }

  /**
   * Removes the previous server's status effects from the client.
   *
   * <p>The effects a server sends are tracked as they pass through the proxy, but a status effect
   * that was never observed (for example one applied while the player was being handed over between
   * servers) would otherwise stay on the client's HUD forever: the destination server does not know
   * about it, so nothing there can clear it either. The vanilla effects every 1.20.2+ client knows
   * are therefore always removed on top of the tracked ones. Removing an effect the player does not
   * have is a no-op on the client, and the destination sends its own effects after the switch.
   */
  private void clearPlayerEffects() {
    if (clientEntityId == null) {
      trackedPlayerEffects.clear();
      return;
    }
    for (int effectId : effectIdsToClear(player.getProtocolVersion(), trackedPlayerEffects)) {
      player.getConnection().delayedWrite(new RemoveEntityEffectPacket(clientEntityId, effectId));
    }
    trackedPlayerEffects.clear();
  }

  /**
   * Returns the player's attributes to the base values the previous server last sent, dropping the
   * modifiers that came with them.
   *
   * <p>From 1.20.5 the extended reach of creative mode is not a client-side property of the game
   * mode: the server adds block and entity interaction range modifiers to the player's attributes
   * and syncs them to the client. The client keeps those modifiers until a server replaces the
   * attribute, and a server only sends the attributes that differ from its own idea of a freshly
   * joined player - a survival destination has nothing to send for interaction range, so a
   * creative-to-survival seamless switch left the player with creative reach. It only went away
   * when a later switch happened to take the non-seamless path, which makes the client build a
   * fresh player and with it a fresh set of attributes.
   *
   * <p>Only the attributes the previous server actually sent are touched, and only their modifiers
   * are dropped: the base values are echoed back unchanged. A destination with attributes of its
   * own sends them right after the switch, which overrides this.
   */
  private void resetPlayerAttributes() {
    if (clientEntityId == null || trackedPlayerAttributes.isEmpty()) {
      trackedPlayerAttributes.clear();
      return;
    }
    player.getConnection().delayedWrite(UpdateAttributesPacket.resetModifiers(
        clientEntityId, trackedPlayerAttributes.values()));
    trackedPlayerAttributes.clear();
  }

  /**
   * Returns the effect ids to remove from the client: the tracked ones plus the vanilla effects
   * that exist in every version able to switch seamlessly.
   *
   * <p>Effect ids are registry ids from 1.20.5 onwards and the one-based legacy ids before that, so
   * the vanilla range shifts by one. It deliberately stops at the effects that shipped in 1.20.2
   * ({@code speed} through {@code darkness}) — an id the client's registry does not contain fails
   * to decode and drops the connection, and later additions are covered by the tracked set.
   */
  static Set<Integer> effectIdsToClear(ProtocolVersion version, Set<Integer> trackedEffects) {
    final Set<Integer> effectIds = new LinkedHashSet<>(trackedEffects);
    final int firstId = version.noLessThan(ProtocolVersion.MINECRAFT_1_20_5) ? 0 : 1;
    for (int i = 0; i < VANILLA_EFFECT_COUNT; i++) {
      effectIds.add(firstId + i);
    }
    return effectIds;
  }

  private void doFastClientServerSwitch(JoinGamePacket joinGame) {
    // In order to handle switching to another server, you will need to send two packets:
    //
    // - The join game packet from the backend server, with a different dimension
    // - A respawn with the correct dimension,
    //
    // Most notably, by having the client accept the join game packet, we can work around the need
    // to perform entity ID rewrites, eliminating potential issues from rewriting packets and
    // improving compatibility with mods.
    RespawnPacket respawn = RespawnPacket.fromJoinGame(joinGame);

    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_16)) {
      // Before Minecraft 1.16, we could not switch to the same dimension without sending an
      // additional respawn. On older versions of Minecraft this forces the client to perform
      // garbage collection which adds additional latency.
      joinGame.setDimension(joinGame.getDimension() == 0 ? -1 : 0);
    }

    player.getConnection().delayedWrite(joinGame);
    player.getConnection().delayedWrite(respawn);
  }

  private void doSafeClientServerSwitch(JoinGamePacket joinGame) {
    // Some clients do not behave well with the "fast" respawn sequence.
    // In this case, we will use a "safe" respawn sequence that involves sending three packets to the client.
    // They have the same effect but tend to work better with buggier clients (Forge 1.8 in particular).

    // Send the JoinGame packet itself, unmodified.
    player.getConnection().delayedWrite(joinGame);

    // Send a respawn packet in a different dimension.
    RespawnPacket fakeSwitchPacket = RespawnPacket.fromJoinGame(joinGame);
    fakeSwitchPacket.setDimension(joinGame.getDimension() == 0 ? -1 : 0);
    player.getConnection().delayedWrite(fakeSwitchPacket);

    // Now send a respawn packet in the correct dimension.
    RespawnPacket correctSwitchPacket = RespawnPacket.fromJoinGame(joinGame);
    player.getConnection().delayedWrite(correctSwitchPacket);
  }

  public Set<UUID> getServerBossBars() {
    return serverBossBars;
  }

  private void clearServerBossBars() {
    if (serverBossBars.isEmpty()) {
      return;
    }
    Set<UUID> snapshot = Set.copyOf(serverBossBars);
    writeBossBarRemoves(snapshot);
    serverBossBars.clear();
    player.getConnection().eventLoop().schedule(() -> {
      if (player.getConnection().isClosed()) {
        return;
      }
      writeBossBarRemoves(snapshot);
      player.getConnection().flush();
    }, 250, TimeUnit.MILLISECONDS);
  }

  private void writeBossBarRemoves(Set<UUID> ids) {
    for (UUID barId : ids) {
      BossBarPacket deletePacket = new BossBarPacket();
      deletePacket.setUuid(barId);
      deletePacket.setAction(BossBarPacket.REMOVE);
      player.getConnection().delayedWrite(deletePacket);
    }
  }

  public Set<Integer> getTrackedEntityIds() {
    return trackedEntityIds;
  }

  public Set<String> getTrackedScoreboardObjectives() {
    return trackedScoreboardObjectives;
  }

  public Set<String> getTrackedScoreboardTeams() {
    return trackedScoreboardTeams;
  }

  public Set<Integer> getTrackedPlayerEffects() {
    return trackedPlayerEffects;
  }

  public Map<String, AttributeSnapshot> getTrackedPlayerAttributes() {
    return trackedPlayerAttributes;
  }

  public @Nullable Integer getClientEntityId() {
    return clientEntityId;
  }

  public boolean isSeamlessPlayActive() {
    return seamlessPlayActive;
  }

  public void setCurrentDimension(int dimension, @Nullable DimensionInfo dimensionInfo) {
    this.currentDimension = dimensionKey(dimension, dimensionInfo);
  }

  private boolean handleCommandTabComplete(TabCompleteRequestPacket packet) {
    // In 1.13+, we need to do additional work for the richer suggestions available.
    String command = packet.getCommand().substring(1);
    int commandEndPosition = command.indexOf(' ');
    if (commandEndPosition == -1) {
      commandEndPosition = command.length();
    }

    String commandLabel = command.substring(0, commandEndPosition);
    if (!server.getCommandManager().hasCommand(commandLabel, player)) {
      if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13)) {
        // Outstanding tab completes are recorded for use with 1.12 clients and below to provide
        // additional tab completion support.
        outstandingTabComplete = packet;
      }

      return false;
    }

    if (!server.getTabCompleteRateLimiter().attempt(player.getUniqueId())) {
      if (server.getConfiguration().isKickOnTabCompleteRateLimit()
          && failedTabCompleteAttempts++ >= server.getConfiguration().getKickAfterRateLimitedTabCompletes()) {
        player.disconnect(Component.translatable("velocity.kick.tab-complete-rate-limit"));
      }

      return true;
    }

    failedTabCompleteAttempts = 0;

    server.getCommandManager().offerBrigadierSuggestions(player, command)
        .thenAcceptAsync(suggestions -> {
          if (suggestions.isEmpty()) {
            return;
          }

          int startPos = -1;
          for (var suggestion : suggestions.getList()) {
            if (startPos == -1 || startPos > suggestion.getRange().getStart()) {
              startPos = suggestion.getRange().getStart();
            }
          }

          if (startPos > 0) {
            List<Offer> offers = new ArrayList<>();
            for (Suggestion suggestion : suggestions.getList()) {
              String offer;
              if (suggestion.getRange().getStart() == startPos) {
                offer = suggestion.getText();
              } else {
                offer = command.substring(startPos, suggestion.getRange().getStart()) + suggestion.getText();
              }

              ComponentHolder tooltip = null;
              if (suggestion.getTooltip() instanceof ComponentLike componentLike) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), componentLike.asComponent());
              } else if (suggestion.getTooltip() != null) {
                tooltip = new ComponentHolder(player.getProtocolVersion(), Component.text(suggestion.getTooltip().getString()));
              }

              offers.add(new Offer(offer, tooltip));
            }

            TabCompleteResponsePacket resp = new TabCompleteResponsePacket();
            resp.setTransactionId(packet.getTransactionId());
            resp.setStart(startPos + 1);
            resp.setLength(packet.getCommand().length() - startPos - 1);
            resp.getOffers().addAll(offers);
            player.getConnection().write(resp);
          }
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          LOGGER.error("Exception while handling command tab completion for player {} executing {}",
              player, command, ex);
          return null;
        });

    return true; // Sorry, handler; we're just going to have to lie to you here.
  }

  private boolean handleRegularTabComplete(TabCompleteRequestPacket packet) {
    // Conduit: short-circuit repeated tab-completes for the same (server, prefix) from a short-TTL
    // cache, absorbing key-held tab spam without round-tripping to the backend.
    if (serveCachedTabCompleteResponse(packet)) {
      return true;
    }

    if (player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13)) {
      // Outstanding tab completes are recorded for use with 1.12 clients and below to provide
      // additional tab completion support.
      outstandingTabComplete = packet;
    }

    return false;
  }

  /**
   * Handles additional tab complete.
   *
   * @param response the tab complete response from the backend
   */
  public void handleTabCompleteResponse(TabCompleteResponsePacket response) {
    if (outstandingTabComplete != null && !outstandingTabComplete.isAssumeCommand()) {
      if (outstandingTabComplete.getCommand().startsWith("/")) {
        this.finishCommandTabComplete(outstandingTabComplete, response);
      } else {
        this.finishRegularTabComplete(outstandingTabComplete, response);
      }
      outstandingTabComplete = null;
    } else {
      // Nothing to do
      player.getConnection().write(response);
    }
  }

  private void finishCommandTabComplete(TabCompleteRequestPacket request,
                                        TabCompleteResponsePacket response) {
    server.getEventManager().fire(new TabCompleteRequestEvent(player, request.getCommand().substring(1)))
        .thenAcceptAsync(e -> {
          String command = e.getPartialMessage();
          server.getCommandManager().offerBrigadierSuggestions(player, command)
              .thenAcceptAsync(offers -> {
                boolean legacy = player.getProtocolVersion().lessThan(ProtocolVersion.MINECRAFT_1_13);
                try {
                  for (Suggestion suggestion : offers.getList()) {
                    String offer = suggestion.getText();
                    offer = legacy && !offer.startsWith("/") ? "/" + offer : offer;
                    if (legacy && offer.startsWith(command)) {
                      offer = offer.substring(command.length());
                    }

                    ComponentHolder tooltip = null;
                    if (suggestion.getTooltip() instanceof ComponentLike componentLike) {
                      tooltip = new ComponentHolder(player.getProtocolVersion(), componentLike.asComponent());
                    } else if (suggestion.getTooltip() != null) {
                      tooltip = new ComponentHolder(player.getProtocolVersion(), Component.text(suggestion.getTooltip().getString()));
                    }

                    response.getOffers().add(new Offer(offer, tooltip));
                  }

                  response.getOffers().sort(null);
                  player.getConnection().write(response);
                } catch (Exception ex) {
                  LOGGER.error("Unable to provide tab list completions for {} for command '{}'", player.getUsername(), command, ex);
                }
              }, player.getConnection().eventLoop()).exceptionally((ex) -> {
                LOGGER.error("Exception while finishing command tab completion,"
                        + " with request {} and response {}",
                    request, response, ex);
                return null;
              });
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          LOGGER.error("Exception while finishing command tab completion,"
                  + " with request {} and response {}",
              request, response, ex);
          return null;
        });
  }

  private void finishRegularTabComplete(TabCompleteRequestPacket request,
                                        TabCompleteResponsePacket response) {
    List<String> offers = new ArrayList<>();
    for (Offer offer : response.getOffers()) {
      offers.add(offer.getText());
    }
    server.getEventManager().fire(new TabCompleteEvent(player, request.getCommand(), offers))
        .thenAcceptAsync(e -> {
          response.getOffers().clear();
          for (String s : e.getSuggestions()) {
            response.getOffers().add(new Offer(s));
          }
          // Conduit: cache the finalised suggestions so identical follow-up requests can be served
          // locally.
          storeTabCompleteResponse(request, response);
          player.getConnection().write(response);
        }, player.getConnection().eventLoop()).exceptionally((ex) -> {
          LOGGER.error(
              "Exception while finishing regular tab completion,"
                  + " with request {} and response {}", request, response, ex);
          return null;
        });
  }

  private boolean serveCachedTabCompleteResponse(TabCompleteRequestPacket request) {
    TabCompleteCache cache = Conduit.get().getTabCompleteCache();
    if (!cache.isEnabled() || player.getCurrentServer().isEmpty()) {
      return false;
    }

    String serverName = player.getCurrentServer().get().getServerInfo().getName();
    // Keyed per player: suggestions are permission-filtered for whoever asked for them.
    var cached = cache.lookup(player.getUniqueId(), serverName, request.getCommand());
    if (cached.isEmpty()) {
      return false;
    }

    TabCompleteCache.CachedResponse cachedResponse = cached.get();
    TabCompleteResponsePacket response = new TabCompleteResponsePacket();
    response.setTransactionId(request.getTransactionId());
    response.setStart(cachedResponse.rangeStart());
    response.setLength(cachedResponse.rangeLength());
    for (String suggestion : cachedResponse.suggestions()) {
      response.getOffers().add(new Offer(suggestion));
    }
    player.getConnection().write(response);
    return true;
  }

  private void storeTabCompleteResponse(TabCompleteRequestPacket request,
                                        TabCompleteResponsePacket response) {
    TabCompleteCache cache = Conduit.get().getTabCompleteCache();
    if (!cache.isEnabled() || player.getCurrentServer().isEmpty()) {
      return;
    }

    List<String> suggestions = new ArrayList<>();
    for (Offer offer : response.getOffers()) {
      suggestions.add(offer.getText());
    }
    String serverName = player.getCurrentServer().get().getServerInfo().getName();
    cache.store(player.getUniqueId(), serverName, request.getCommand(),
        new TabCompleteCache.CachedResponse(
            suggestions,
            response.getTransactionId(),
            response.getStart(),
            response.getLength()));
  }

  /**
   * Immediately send any queued messages to the server.
   */
  public void flushQueuedMessages() {
    VelocityServerConnection serverConnection = player.getConnectedServer();
    if (serverConnection != null) {
      MinecraftConnection connection = serverConnection.getConnection();
      if (connection != null) {
        PluginMessagePacket pm;
        while ((pm = loginPluginMessages.poll()) != null) {
          connection.write(pm);
        }

        loginPluginMessagesBytes.set(0);
        loginPluginMessagesCount.set(0);
      }
    }
  }
}
