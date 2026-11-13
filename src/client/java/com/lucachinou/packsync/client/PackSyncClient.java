package com.lucachinou.packsync.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import com.lucachinou.packsync.Protocol;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class PackSyncClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientConfigurationConnectionEvents.INIT.register((handler, client) ->
                PackDownload.beginConfiguration(client, () -> ((com.lucachinou.packsync.mixin.client.ClientCommonPacketListenerAccessor) handler)
                        .packsync$connection().disconnect(Component.literal("Server resource pack installation cancelled or failed"))));
        ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> PackDownload.disconnect(client));
        ClientConfigurationNetworking.registerGlobalReceiver(Protocol.Catalog.TYPE,
                (packet, context) -> PackDownload.configurationCatalog(context.client(), packet));
        ClientConfigurationNetworking.registerGlobalReceiver(Protocol.Chunk.TYPE,
                (packet, context) -> PackDownload.chunk(context.client(), packet));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> PackDownload.start(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PackDownload.disconnect(client));
        ClientPlayNetworking.registerGlobalReceiver(com.lucachinou.packsync.Protocol.Catalog.TYPE,
                (packet, context) -> PackDownload.catalog(context.client(), packet));
        ClientPlayNetworking.registerGlobalReceiver(com.lucachinou.packsync.Protocol.Chunk.TYPE,
                (packet, context) -> PackDownload.chunk(context.client(), packet));
        ClientPlayNetworking.registerGlobalReceiver(com.lucachinou.packsync.Protocol.Delta.TYPE,
                (packet, context) -> PackDownload.delta(context.client(), packet));
        ClientTickEvents.END_CLIENT_TICK.register(PackDownload::tick);
    }
}
