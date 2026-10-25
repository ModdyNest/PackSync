package com.lucachinou.packsync.client;

import com.lucachinou.packsync.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.*;
import net.minecraft.network.chat.Component;
import net.minecraft.SharedConstants;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** PoC: stop-and-wait chunks bound memory usage and never enqueue a whole ZIP on Netty. */
public final class PackDownload {
    public static final List<Pack> ACTIVE = new ArrayList<>();
    private static List<Protocol.Entry> entries = List.of();
    private static List<Protocol.Entry> announced = List.of();
    private static List<Protocol.Entry> target = List.of();
    private static List<Protocol.Entry> pending;
    // Only reuse files verified during this connection, never trust arbitrary cache contents.
    private static final Map<String, Path> downloaded = new HashMap<>();
    private static OutputStream output;
    private static Path temp, directory;
    private static int index;
    private static long offset, received, total, lastActivity, started;
    private static boolean waiting, busy;
    private static DownloadScreen screen;
    private static net.minecraft.client.gui.screens.Screen previous;
    public static void start(Minecraft client) {
        if (!ClientPlayNetworking.canSend(Protocol.Request.TYPE)) {
            PackSync.LOGGER.info("The server does not have PackSync installed; skipping resource synchronization.");
            return;
        }
        waiting = true; lastActivity = System.nanoTime();
        ClientPlayNetworking.send(new Protocol.Request("", 0));
    }
    public static void catalog(Minecraft client, Protocol.Catalog catalog) {
        if (!waiting || busy) return;
        waiting = false;
        try {
            validate(catalog.entries());
            announced = List.copyOf(catalog.entries());
            synchronize(client, announced);
        } catch (Exception e) { fail(client,e); }
    }
    public static void delta(Minecraft client, Protocol.Delta delta) {
        try {
            var updated = CatalogDiff.apply(announced, delta);
            validate(updated);
            announced = updated;
            // Coalesce successive reloads, without interrupting the current ZIP or resource reload.
            pending = updated;
        } catch (Exception e) {
            PackSync.LOGGER.warn("Ignoring an invalid resource pack update", e);
        }
    }
    private static void validate(List<Protocol.Entry> catalog) throws IOException {
        if (catalog.size() > Protocol.MAX_PACKS) throw new IOException("Too many packs");
        Set<String> ids = new HashSet<>();
        for (var entry : catalog) {
            if (!ids.add(entry.id()) || entry.size() <= 0 || entry.size() > Protocol.MAX_PACK_SIZE
                    || !entry.sha256().matches("[0-9a-f]{64}")) throw new IOException("Invalid resource pack catalog");
        }
    }
    private static void synchronize(Minecraft client, List<Protocol.Entry> catalog) {
        try {
            target = catalog.stream().sorted(Comparator.comparing(Protocol.Entry::priority).thenComparing(Protocol.Entry::id)).toList();
            Set<String> scheduled = new HashSet<>();
            entries = target.stream().filter(e -> !downloaded.containsKey(e.sha256()) || !Files.isRegularFile(downloaded.get(e.sha256())))
                    .filter(e -> scheduled.add(e.sha256())).toList();
            total = 0;
            for (var e : entries) {
                total = Math.addExact(total, e.size());
            }
            if (entries.isEmpty()) {
                if (!ACTIVE.isEmpty() || !target.isEmpty()) apply(client);
                return;
            }
            directory = client.gameDirectory.toPath().resolve(PackSync.CACHE_DIRECTORY);
            Files.createDirectories(directory);
            index = 0; received = 0; busy = true;
            started = lastActivity = System.nanoTime();
            previous = client.gui.screen(); screen = new DownloadScreen(); client.gui.setScreen(screen);
            next(client);
        } catch (Exception e) { fail(client,e); }
    }
    private static void next(Minecraft client) throws IOException {
        if (index == entries.size()) { apply(client); return; }
        offset = 0;
        temp = Files.createTempFile(directory,"download-", ".part");
        output = Files.newOutputStream(temp);
        request();
    }
    private static void request() {
        lastActivity = System.nanoTime();
        ClientPlayNetworking.send(new Protocol.Request(entries.get(index).sha256(),offset));
    }
    public static void chunk(Minecraft client, Protocol.Chunk chunk) {
        if (!busy || index >= entries.size()) return;
        try {
            var entry = entries.get(index);
            if (!entry.sha256().equals(chunk.hash()) || chunk.offset() != offset || chunk.data().length == 0 || offset + chunk.data().length > entry.size())
                throw new IOException("Invalid resource pack chunk");
            output.write(chunk.data()); offset += chunk.data().length; received += chunk.data().length;
            if (offset == entry.size()) {
                output.close(); output = null;
                if (!PackCatalog.hash(temp).equals(entry.sha256())) throw new IOException("SHA-256 mismatch for " + entry.id());
                Path target = directory.resolve(entry.sha256() + ".zip");
                Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); temp = null;
                downloaded.put(entry.sha256(),target); index++; next(client);
            } else request();
        } catch (Exception e) { fail(client,e); }
    }
    private static void apply(Minecraft client) throws IOException {
        List<Pack> packs = new ArrayList<>();
        for (int i=0;i<target.size();i++) {
            var e = target.get(i);
            var location = new PackLocationInfo(PackSync.MOD_ID + "/" + i, Component.literal(e.id() + " [" + e.priority() + "]"), PackSource.SERVER, Optional.empty());
            var supplier = new FilePackResources.FileResourcesSupplier(downloaded.get(e.sha256()));
            var metadata = Pack.readPackMetadata(location,supplier,SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES),PackType.CLIENT_RESOURCES);
            if (metadata == null) throw new IOException("Invalid pack.mcmeta: " + e.id());
            packs.add(new Pack(location,supplier,metadata,new PackSelectionConfig(false, Pack.Position.TOP,true)));
        }
        ACTIVE.clear(); ACTIVE.addAll(packs); busy = false;
        restore(client);
        client.reloadResourcePacks();
        PackSync.LOGGER.info("{} resource packs synchronized and queued for loading", packs.size());
    }
    public static void tick(Minecraft client) {
        if (pending != null && !busy && !waiting && client.getConnection() != null && client.gui.overlay() == null) {
            var update = pending;
            pending = null;
            synchronize(client,update);
        }
        // Initial terrain-loading screens can replace us after JOIN. Only replace those,
        // never a disconnect screen or a server dialog.
        if (busy && screen != null && client.level != null && client.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) {
            previous = null;
            client.gui.setScreen(screen);
        }
        if ((waiting || busy) && System.nanoTime()-lastActivity > (waiting ? 5L : 30L)*1_000_000_000L) {
            if (waiting) {
                waiting = false;
                PackSync.LOGGER.info("The server did not respond to PackSync (missing or incompatible); skipping resource synchronization.");
            } else fail(client,new IOException("Resource pack download timed out"));
        }
    }
    private static void restore(Minecraft client) {
        if (screen != null && client.gui.screen() == screen) {
            // A terrain screen captured during JOIN would otherwise reopen after loading.
            if (client.level != null && previous instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) previous = null;
            client.gui.setScreen(previous);
        }
        screen = null; previous = null;
    }
    private static void fail(Minecraft client, Exception e) {
        PackSync.LOGGER.warn("Resource synchronization aborted; keeping the connection open",e);
        cleanup(); restore(client);
    }
    private static void cleanup() {
        waiting = busy = false;
        try { if(output != null) output.close(); } catch(IOException ignored) { }
        output = null;
        try { if(temp != null) Files.deleteIfExists(temp); } catch(IOException ignored) { }
        temp = null;
    }
    public static void disconnect(Minecraft client) {
        cleanup(); restore(client);
        announced = target = entries = List.of(); pending = null; downloaded.clear();
        boolean hadPacks = !ACTIVE.isEmpty(); ACTIVE.clear();
        if (hadPacks) client.reloadResourcePacks();
    }
    static double progress() { return total == 0 ? 0 : (double)received/total; }
    static String counts() { return index + " / " + entries.size() + " resource packs downloaded"; }
    static String details() {
        double seconds = Math.max(0.001,(System.nanoTime()-started)/1e9);
        return String.format(Locale.ROOT,"%.2f / %.2f MB — %.2f MB/s", received/1e6,total/1e6,received/1e6/seconds);
    }
    static String current() { return index < entries.size() ? entries.get(index).id() : "Loading…"; }
}
