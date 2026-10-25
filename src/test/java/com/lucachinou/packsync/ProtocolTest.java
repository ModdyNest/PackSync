package com.lucachinou.packsync;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void deltaRoundTrip() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var delta = new Protocol.Delta(List.of(new Protocol.Entry("file/new",123,"a".repeat(64),Protocol.Priority.HIGH)),List.of("file/removed"));
            Protocol.Delta.CODEC.encode(buffer,delta);
            assertEquals(delta,Protocol.Delta.CODEC.decode(buffer));
            assertEquals(0,buffer.readableBytes());
        } finally { buffer.release(); }
    }

    @Test void rejectsOversizedDelta() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(Protocol.MAX_PACKS+1);
            assertThrows(IllegalArgumentException.class,() -> Protocol.Delta.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void rejectsOversizedRemovalList() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(0);
            buffer.writeVarInt(Protocol.MAX_PACKS+1);
            assertThrows(IllegalArgumentException.class,() -> Protocol.Delta.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void catalogRoundTrip() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var catalog = new Protocol.Catalog(List.of(new Protocol.Entry("file/demo",123456,"a".repeat(64),Protocol.Priority.CRITICAL)));
            Protocol.Catalog.CODEC.encode(buffer,catalog);
            assertEquals(catalog,Protocol.Catalog.CODEC.decode(buffer));
            assertEquals(0,buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void chunkRoundTrip() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            byte[] bytes = new byte[Protocol.CHUNK_SIZE];
            new java.util.Random(42).nextBytes(bytes);
            Protocol.Chunk.CODEC.encode(buffer,new Protocol.Chunk("b".repeat(64),65536,bytes));
            var decoded = Protocol.Chunk.CODEC.decode(buffer);
            assertEquals(65536,decoded.offset());
            assertArrayEquals(bytes,decoded.data());
        } finally { buffer.release(); }
    }
    @Test void rejectsOversizedCatalog() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(Protocol.MAX_PACKS+1);
            assertThrows(IllegalArgumentException.class,() -> Protocol.Catalog.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
