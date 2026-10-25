package com.lucachinou.packsync.mixin.client;

import com.lucachinou.packsync.client.PackDownload;
import com.lucachinou.packsync.PackSync;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.*;

@Mixin(PackRepository.class)
public class PackRepositoryMixin {
    @Unique
    private boolean packsync$isClientRepository() {
        var mc = Minecraft.getInstance();
        return mc != null && mc.getResourcePackRepository() == (Object)this;
    }
    @Inject(method="discoverAvailable",at=@At("RETURN"),cancellable=true)
    private void addDownloaded(CallbackInfoReturnable<Map<String,Pack>> cir) {
        if (!packsync$isClientRepository()) return;
        Map<String,Pack> packs = new TreeMap<>(cir.getReturnValue());
        for (var pack : PackDownload.ACTIVE) packs.put(pack.getId(),pack);
        cir.setReturnValue(Map.copyOf(packs));
    }
    @Inject(method="reload",at=@At("RETURN"))
    private void orderDownloaded(CallbackInfo ci) {
        if (!packsync$isClientRepository()) return;
        var self = (PackRepository)(Object)this;
        List<String> selected = new ArrayList<>(self.getSelectedIds());
        selected.removeIf(id -> id.startsWith(PackSync.MOD_ID + "/"));
        // Repository order is low -> high; Minecraft displays this list reversed.
        for (var pack : PackDownload.ACTIVE) selected.add(pack.getId());
        self.setSelected(selected);
    }
}
