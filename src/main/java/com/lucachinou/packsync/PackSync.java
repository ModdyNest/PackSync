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

public class PackSync implements ModInitializer {
    public static final String MOD_ID = "packsync";
    public static final String CACHE_DIRECTORY = MOD_ID + "-cache";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private PackCatalog.Snapshot catalog = PackCatalog.Snapshot.EMPTY;
    private final Map<net.minecraft.server.network.ServerGamePacketListenerImpl, PackCatalog.Snapshot> sessions = new WeakHashMap<>();

    @Override
    public void onInitialize() {
        Protocol.register();
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
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { sessions.clear(); catalog = PackCatalog.Snapshot.EMPTY; });
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
            var path = snapshot.files().get(request.hash());
            if (path == null || request.offset() < 0) return;
            try (var file = new RandomAccessFile(path.toFile(), "r")) {
                if (request.offset() >= file.length()) return;
                file.seek(request.offset());
                byte[] bytes = new byte[(int)Math.min(Protocol.CHUNK_SIZE, file.length() - request.offset())];
                file.readFully(bytes);
                ServerPlayNetworking.send(context.player(), new Protocol.Chunk(request.hash(), request.offset(), bytes));
            } catch (Exception e) { LOGGER.warn("Failed to send a resource pack", e); }
        });
    }
}
