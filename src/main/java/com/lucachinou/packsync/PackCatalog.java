package com.lucachinou.packsync;

import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Immutable snapshots: downloads remain valid across /reload through content-addressed files. */
public final class PackCatalog {
    public record Snapshot(List<Protocol.Entry> entries, Map<String, Path> files) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), Map.of());
    }
    public static Snapshot scan(MinecraftServer server) {
        List<Protocol.Entry> entries = new ArrayList<>();
        Map<String, Path> files = new HashMap<>();
        Path cache = server.getServerDirectory().resolve(PackSync.CACHE_DIRECTORY);
        for (var pack : server.getPackRepository().getSelectedPacks()) {
            if (entries.size() >= Protocol.MAX_PACKS) break;
            try (var resources = pack.openMetadata()) {
                var zip = resources.getRootResource("ressources.zip");
                if (zip == null) continue;
                Files.createDirectories(cache);
                Path temp = Files.createTempFile(cache, "scan-", ".tmp");
                try {
                    try (var in = zip.get(); var out = Files.newOutputStream(temp)) {
                        copyLimited(in, out, Protocol.MAX_PACK_SIZE);
                    }
                    try (ZipFile check = new ZipFile(temp.toFile())) {
                        if (check.getEntry("pack.mcmeta") == null) throw new IOException("Missing pack.mcmeta at the root of the ZIP");
                    }
                    Protocol.Priority priority = Protocol.Priority.MEDIUM;
                    var config = resources.getRootResource("ressources.json");
                    if (config != null) {
                        try (var in = config.get()) {
                            var json = JsonParser.parseString(new String(in.readNBytes(8193), java.nio.charset.StandardCharsets.UTF_8));
                            String value = json.isJsonPrimitive() ? json.getAsString() : json.getAsJsonObject().get("priority").getAsString();
                            priority = Protocol.Priority.valueOf(value);
                        } catch (Exception e) {
                            PackSync.LOGGER.warn("{}: invalid ressources.json; using MEDIUM priority", pack.getId());
                        }
                    }
                    String hash = hash(temp);
                    Path target = cache.resolve(hash + ".zip");
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                    files.put(hash, target);
                    entries.add(new Protocol.Entry(pack.getId(), Files.size(target), hash, priority));
                } finally { Files.deleteIfExists(temp); }
            } catch (Exception e) {
                PackSync.LOGGER.warn("Unable to distribute resources from {}", pack.getId(), e);
            }
        }
        PackSync.LOGGER.info("Catalog rebuilt: {} resource pack(s)", entries.size());
        return new Snapshot(List.copyOf(entries), Map.copyOf(files));
    }
    public static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) { in.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void copyLimited(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) throw new IOException("Resource pack exceeds the maximum allowed size");
            out.write(buffer,0,read);
        }
    }
}
