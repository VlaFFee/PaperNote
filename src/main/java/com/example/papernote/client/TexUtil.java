package com.example.papernote.client;

import com.example.papernote.PaperNoteMod;
import com.example.papernote.image.ImageUtil;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.atomic.AtomicLong;

/** Создание и освобождение динамических текстур. Вызывается только из главного потока. */
final class TexUtil {
    record Tex(ResourceLocation loc, int w, int h) {}

    private static final AtomicLong COUNTER = new AtomicLong();

    private TexUtil() {}

    static Tex create(ImageUtil.Decoded d, String kind) {
        NativeImage img = new NativeImage(d.w(), d.h(), false);
        int[] px = d.argb();
        for (int y = 0; y < d.h(); y++) {
            int row = y * d.w();
            for (int x = 0; x < d.w(); x++) {
                int argb = px[row + x];
                int abgr = (argb & 0xFF00FF00) | ((argb & 0xFF) << 16) | ((argb >> 16) & 0xFF);
                img.setPixelRGBA(x, y, abgr);
            }
        }
        DynamicTexture tex = new DynamicTexture(img);
        tex.setFilter(true, false); // мягкое сглаживание при масштабировании фото
        ResourceLocation loc = new ResourceLocation(PaperNoteMod.MODID, kind + "/" + COUNTER.incrementAndGet());
        Minecraft.getInstance().getTextureManager().register(loc, tex);
        return new Tex(loc, d.w(), d.h());
    }

    static void release(Tex t) {
        Minecraft.getInstance().getTextureManager().release(t.loc());
    }
}
