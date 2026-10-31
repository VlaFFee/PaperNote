package com.example.papernote.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;

public final class ClientHooks {
    private ClientHooks() {}

    public static void openNote(InteractionHand hand) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new NoteScreen(hand));
        }
    }
}
