package com.lucachinou.packsync;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.ArrayList;
import java.util.List;

public final class Protocol {
    public static final int CHUNK_SIZE = 256 * 1024;
    public static final long MAX_PACK_SIZE = 512L * 1024 * 1024;
    public static final int MAX_PACKS = 128;
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
    public record Entry(String id, long size, String sha256, Priority priority) {
        void write(FriendlyByteBuf b) {
            b.writeUtf(id, 1024); b.writeVarLong(size); b.writeUtf(sha256, 64); b.writeEnum(priority);
        }
        static Entry read(FriendlyByteBuf b) {
            return new Entry(b.readUtf(1024), b.readVarLong(), b.readUtf(64), b.readEnum(Priority.class));
        }
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(PackSync.MOD_ID, name));
    }
    public record Request(String hash, long offset) implements CustomPacketPayload {
        public static final Type<Request> TYPE = Protocol.type("request");
        public static final StreamCodec<FriendlyByteBuf, Request> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeUtf(p.hash,64); b.writeVarLong(p.offset); },
                b -> new Request(b.readUtf(64), b.readVarLong()));
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Catalog(List<Entry> entries) implements CustomPacketPayload {
        public static final Type<Catalog> TYPE = Protocol.type("catalog");
        public static final StreamCodec<FriendlyByteBuf, Catalog> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeVarInt(p.entries.size()); p.entries.forEach(e -> e.write(b)); }, b -> {
                    int count = b.readVarInt();
                    if (count < 0 || count > MAX_PACKS) throw new IllegalArgumentException("Too many packs");
                    List<Entry> entries = new ArrayList<>();
                    for (int i=0;i<count;i++) entries.add(Entry.read(b));
                    return new Catalog(List.copyOf(entries));
                });
        @Override public Type<Catalog> type() { return TYPE; }
    }
    public record Chunk(String hash, long offset, byte[] data) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = Protocol.type("chunk");
        public static final StreamCodec<FriendlyByteBuf, Chunk> CODEC = CustomPacketPayload.codec(
                (p,b) -> { b.writeUtf(p.hash,64); b.writeVarLong(p.offset); b.writeByteArray(p.data); },
                b -> new Chunk(b.readUtf(64), b.readVarLong(), b.readByteArray(CHUNK_SIZE)));
        @Override public Type<Chunk> type() { return TYPE; }
    }
    public record Delta(List<Entry> changed, List<String> removed) implements CustomPacketPayload {
        public static final Type<Delta> TYPE = Protocol.type("delta");
        public static final StreamCodec<FriendlyByteBuf, Delta> CODEC = CustomPacketPayload.codec(
                (p,b) -> {
                    b.writeVarInt(p.changed.size()); p.changed.forEach(e -> e.write(b));
                    b.writeVarInt(p.removed.size()); p.removed.forEach(id -> b.writeUtf(id,1024));
                }, b -> {
                    int count = readCount(b);
                    List<Entry> changed = new ArrayList<>();
                    for (int i=0;i<count;i++) changed.add(Entry.read(b));
                    count = readCount(b);
                    List<String> removed = new ArrayList<>();
                    for (int i=0;i<count;i++) removed.add(b.readUtf(1024));
                    return new Delta(List.copyOf(changed),List.copyOf(removed));
                });
        public boolean isEmpty() { return changed.isEmpty() && removed.isEmpty(); }
        @Override public Type<Delta> type() { return TYPE; }
    }
    private static int readCount(FriendlyByteBuf b) {
        int count = b.readVarInt();
        if (count < 0 || count > MAX_PACKS) throw new IllegalArgumentException("Too many packs");
        return count;
    }
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Catalog.TYPE, Catalog.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Chunk.TYPE, Chunk.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Delta.TYPE, Delta.CODEC);
    }
}
