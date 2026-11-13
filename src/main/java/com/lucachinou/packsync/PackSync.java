package com.lucachinou.packsync;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.Packet;

public class PackSync implements ModInitializer {
    public static final String MOD_ID = "packsync";
    public static final String CACHE_DIRECTORY = MOD_ID + "-cache";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private PackCatalog.Snapshot catalog = PackCatalog.Snapshot.EMPTY;
    private final Map<net.minecraft.server.network.ServerGamePacketListenerImpl, PackCatalog.Snapshot> sessions = new WeakHashMap<>();
    private final Map<ServerConfigurationPacketListenerImpl, PackCatalog.Snapshot> configuring = new WeakHashMap<>();
    private final Map<UUID, PackCatalog.Snapshot> configured = new HashMap<>();
    private static final ConfigurationTask.Type SYNC_TASK = new ConfigurationTask.Type("packsync:install");

    @Override
    public void onInitialize() {
        Protocol.register();
        ServerConfigurationConnectionEvents.CONFIGURE.register((handler, server) -> {
            if (!ServerConfigurationNetworking.canSend(handler, Protocol.Catalog.TYPE)
                    || !ServerConfigurationNetworking.canSend(handler, Protocol.Chunk.TYPE)) return;
            var snapshot = catalog;
            ((FabricServerConfigurationPacketListenerImpl) handler).addTask(new ConfigurationTask() {
                @Override public Type type() { return SYNC_TASK; }
                @Override public void start(Consumer<Packet<?>> sender) {
                    configuring.put(handler, snapshot);
                    sender.accept(ServerConfigurationNetworking.createClientboundPacket(new Protocol.Catalog(snapshot.entries())));
                }
            });
        });
        ServerConfigurationNetworking.registerGlobalReceiver(Protocol.Request.TYPE, (request, context) -> {
            var snapshot = configuring.get(context.packetListener());
            if (snapshot != null) sendChunk(snapshot, request, packet -> ServerConfigurationNetworking.send(context.packetListener(), packet));
        });
        ServerConfigurationNetworking.registerGlobalReceiver(Protocol.Ready.TYPE, (packet, context) -> {
            var handler = context.packetListener();
            var snapshot = configuring.remove(handler);
            if (snapshot == null) return;
            configured.put(handler.getOwner().id(), snapshot);
            ((FabricServerConfigurationPacketListenerImpl) handler).completeTask(SYNC_TASK);
        });
        ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> {
            configuring.remove(handler);
            configured.remove(handler.getOwner().id());
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var snapshot = configured.remove(handler.getOwner().id());
            if (snapshot == null) return;
            var files = new HashMap<>(snapshot.files());
            files.putAll(catalog.files());
            sessions.put(handler, new PackCatalog.Snapshot(catalog.entries(), Map.copyOf(files)));
            var delta = CatalogDiff.between(snapshot.entries(), catalog.entries());
            if (!delta.isEmpty() && ServerPlayNetworking.canSend(handler.player, Protocol.Delta.TYPE))
                ServerPlayNetworking.send(handler.player, delta);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> catalog = PackCatalog.scan(server));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
            if (!success) return;
            var updated = PackCatalog.scan(server);
            var delta = CatalogDiff.between(catalog.entries(), updated.entries());
            catalog = updated;
            if (delta.isEmpty()) return;
            for (var player : server.getPlayerList().getPlayers()) {
                var snapshot = sessions.get(player.connection);
                if (snapshot == null || !ServerPlayNetworking.canSend(player, Protocol.Delta.TYPE)) continue;
                // Keep old hashes available while the client finishes an in-flight transfer.
                var files = new HashMap<>(snapshot.files());
                files.putAll(updated.files());
                sessions.put(player.connection, new PackCatalog.Snapshot(updated.entries(), Map.copyOf(files)));
                ServerPlayNetworking.send(player, delta);
            }
            LOGGER.info("Resource pack changes: {} added/updated, {} removed", delta.changed().size(), delta.removed().size());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { sessions.clear(); configuring.clear(); configured.clear(); catalog = PackCatalog.Snapshot.EMPTY; });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> sessions.remove(handler));
        ServerPlayNetworking.registerGlobalReceiver(Protocol.Request.TYPE, (request, context) -> {
            var handler = context.player().connection;
            if (request.hash().isEmpty()) {
                sessions.put(handler, catalog);
                ServerPlayNetworking.send(context.player(), new Protocol.Catalog(catalog.entries()));
                return;
            }
            var snapshot = sessions.get(handler);
            if (snapshot == null) return;
            sendChunk(snapshot, request, packet -> ServerPlayNetworking.send(context.player(), packet));
        });
    }
    private static void sendChunk(PackCatalog.Snapshot snapshot, Protocol.Request request, Consumer<Protocol.Chunk> sender) {
            var path = snapshot.files().get(request.hash());
            if (path == null || request.offset() < 0) return;
            try (var file = new RandomAccessFile(path.toFile(), "r")) {
                if (request.offset() >= file.length()) return;
                file.seek(request.offset());
                byte[] bytes = new byte[(int)Math.min(Protocol.CHUNK_SIZE, file.length() - request.offset())];
                file.readFully(bytes);
                sender.accept(new Protocol.Chunk(request.hash(), request.offset(), bytes));
            } catch (Exception e) { LOGGER.warn("Failed to send a resource pack", e); }
    }
}
