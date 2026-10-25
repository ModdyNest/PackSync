package com.lucachinou.packsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackCatalogTest {
    @TempDir Path directory;

    @Test void hashesKnownContent() throws IOException {
        Path file = directory.resolve("test.zip");
        Files.writeString(file,"abc");
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",PackCatalog.hash(file));
    }

    @Test void acceptsExactSizeAndRejectsOverflow() throws IOException {
        byte[] bytes = new byte[65537];
        var output = new ByteArrayOutputStream();
        PackCatalog.copyLimited(new ByteArrayInputStream(bytes),output,bytes.length);
        assertArrayEquals(bytes,output.toByteArray());
        assertThrows(IOException.class,() -> PackCatalog.copyLimited(new ByteArrayInputStream(bytes),OutputStream.nullOutputStream(),bytes.length-1));
    }

    @Test void priorityOrderMatchesMinecraftLowToHigh() {
        var priorities = new ArrayList<>(List.of(Protocol.Priority.CRITICAL,Protocol.Priority.LOW,Protocol.Priority.HIGH,Protocol.Priority.MEDIUM));
        priorities.sort(Comparator.naturalOrder());
        assertEquals(List.of(Protocol.Priority.LOW,Protocol.Priority.MEDIUM,Protocol.Priority.HIGH,Protocol.Priority.CRITICAL),priorities);
    }
}
