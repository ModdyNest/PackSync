package com.lucachinou.packsync.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class DownloadScreen extends Screen {
    public DownloadScreen() { super(Component.literal("Downloading server resources")); }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta) {
        super.extractRenderState(graphics,mouseX,mouseY,delta);
        int x = width/2, y = height/2;
        int half = Math.min(150, Math.max(30,width/2-12));
        graphics.centeredText(font,title,x,y-55,0xFFFFFFFF);
        graphics.centeredText(font,PackDownload.counts(),x,y-30,0xFFFFFFFF);
        graphics.centeredText(font,font.plainSubstrByWidth(PackDownload.current(),half*2),x,y-15,0xFFAAAAAA);
        graphics.fill(x-half-1,y-1,x+half+1,y+9,0xFFFFFFFF);
        graphics.fill(x-half,y,x+half,y+8,0xFF000000);
        graphics.fill(x-half,y,x-half+(int)(half*2*PackDownload.progress()),y+8,0xFF55FF55);
        graphics.centeredText(font,Math.round(PackDownload.progress()*100)+"%",x,y+20,0xFFFFFFFF);
        graphics.centeredText(font,PackDownload.details(),x,y+38,0xFFAAAAAA);
    }
}
