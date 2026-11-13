package com.lucachinou.packsync.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Locale;
import java.util.function.Consumer;

final class ConsentScreen extends Screen {
    private final int count;
    private final long size;
    private final Consumer<Boolean> install;
    private final Runnable disconnect;
    private boolean remember, decided;

    ConsentScreen(int count, long size, Consumer<Boolean> install, Runnable disconnect) {
        super(Component.literal("Install server resource packs?"));
        this.count = count; this.size = size; this.install = install; this.disconnect = disconnect;
    }

    @Override protected void init() {
        int x = width / 2, y = height / 2;
        addRenderableWidget(Checkbox.builder(Component.literal("Remember for this server"), font)
                .pos(x - 150, y + 5).maxWidth(300).selected(remember)
                .onValueChange((checkbox, value) -> remember = value).build());
        addRenderableWidget(Button.builder(Component.literal("Install"), button -> {
            if (!decided) { decided = true; install.accept(remember); }
        }).bounds(x - 155, y + 40, 150, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Disconnect"), button -> {
            if (!decided) { decided = true; disconnect.run(); }
        }).bounds(x + 5, y + 40, 150, 20).build());
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int x = width / 2, y = height / 2;
        graphics.centeredText(font, title, x, y - 65, 0xFFFFFFFF);
        graphics.centeredText(font, "Allow PackSync to download and activate these resources?", x, y - 43, 0xFFFFFFFF);
        graphics.centeredText(font, String.format(Locale.ROOT, "%d resource packs — %.2f MB", count, size / 1e6), x, y - 26, 0xFFAAAAAA);
        graphics.centeredText(font, "Only install resources from servers you trust.", x, y - 10, 0xFFFFCC55);
    }
}
