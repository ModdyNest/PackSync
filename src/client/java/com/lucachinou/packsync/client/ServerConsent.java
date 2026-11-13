package com.lucachinou.packsync.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lucachinou.packsync.PackSync;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Address-based convenience setting, not authentication of a server's identity. */
final class ServerConsent {
    private final Path file;
    private final Set<String> servers = new HashSet<>();

    ServerConsent(Path file) {
        this.file = file;
        try {
            if (Files.isRegularFile(file)) {
                Set<String> stored = new Gson().fromJson(Files.readString(file), new TypeToken<Set<String>>() { }.getType());
                if (stored != null) servers.addAll(stored);
            }
        } catch (Exception e) { PackSync.LOGGER.warn("Unable to read remembered PackSync permissions", e); }
    }

    boolean allows(String server) { return server != null && servers.contains(server); }

    void remember(String server) {
        if (server == null) return;
        servers.add(server);
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), "packsync-permissions-", ".tmp");
            try {
                Files.writeString(temporary, new Gson().toJson(servers));
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { PackSync.LOGGER.warn("Unable to save remembered PackSync permission", e); }
    }
}
