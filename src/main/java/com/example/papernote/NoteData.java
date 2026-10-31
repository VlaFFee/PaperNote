package com.example.papernote;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Чтение и запись содержимого записки в NBT предмета. */
public final class NoteData {
    public static final String TEXT = "Text";
    public static final String PHOTO = "Photo";
    public static final int MAX_TEXT = 800;

    private NoteData() {}

    public static String text(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(TEXT);
    }

    public static String photo(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(PHOTO);
    }

    public static void write(ItemStack stack, String text, String photo) {
        if (text.isEmpty() && photo.isEmpty()) {
            stack.removeTagKey(TEXT);
            stack.removeTagKey(PHOTO);
            CompoundTag tag = stack.getTag();
            if (tag != null && tag.isEmpty()) {
                stack.setTag(null);
            }
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        if (text.isEmpty()) tag.remove(TEXT); else tag.putString(TEXT, text);
        if (photo.isEmpty()) tag.remove(PHOTO); else tag.putString(PHOTO, photo);
    }
}
