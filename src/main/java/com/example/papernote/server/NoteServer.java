package com.example.papernote.server;

import com.example.papernote.NoteData;
import com.example.papernote.NoteItem;
import com.example.papernote.net.Packets;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/** Серверная часть сохранения записки. Право редактирования — только у игроков в творческом режиме. */
public final class NoteServer {
    private NoteServer() {}

    public static void save(ServerPlayer player, Packets.Save pk) {
        if (player == null || !player.isCreative()) return;

        InteractionHand[] hands = InteractionHand.values();
        if (pk.hand < 0 || pk.hand >= hands.length) return;
        ItemStack stack = player.getItemInHand(hands[pk.hand]);
        if (!(stack.getItem() instanceof NoteItem)) return;

        String text = sanitize(pk.text);

        // Фото принимаем, только если оно уже лежит в хранилище сервера; иначе оставляем прежнее
        String photo = pk.photo == null ? "" : pk.photo;
        if (!photo.isEmpty() && !PhotoStore.exists(player.getServer(), photo)) {
            photo = NoteData.photo(stack);
        }

        NoteData.write(stack, text, photo);
        player.getInventory().setChanged();
    }

    private static String sanitize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), NoteData.MAX_TEXT));
        for (int i = 0; i < raw.length() && sb.length() < NoteData.MAX_TEXT; i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c >= 32 && c != 127) sb.append(c);
        }
        return sb.toString();
    }
}
