package com.example.papernote.client;

import com.example.papernote.image.ImageUtil;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Превью фотографий для окна выбора: грузятся лениво, только видимые, старые выгружаются (LRU). */
final class Thumbs {
    private static final int SIZE = 96;
    private static final int MAX_LOADED = 80;

    private static final Map<Path, TexUtil.Tex> LOADED = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, TexUtil.Tex> eldest) {
            if (size() > MAX_LOADED) {
                TexUtil.release(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private static final Set<Path> LOADING = new HashSet<>();
    private static final Set<Path> FAILED = new HashSet<>();

    private Thumbs() {}

    static TexUtil.Tex get(Path path) {
        TexUtil.Tex t = LOADED.get(path);
        if (t != null) return t;
        if (LOADING.contains(path) || FAILED.contains(path)) return null;
        LOADING.add(path);
        Async.IO.execute(() -> {
            ImageUtil.Decoded d = ImageUtil.read(path, SIZE);
            Minecraft.getInstance().execute(() -> {
                LOADING.remove(path);
                if (d == null) {
                    FAILED.add(path);
                } else {
                    LOADED.put(path, TexUtil.create(d, "thumb"));
                }
            });
        });
        return null;
    }

    static boolean failed(Path path) {
        return FAILED.contains(path);
    }

    /** Забыть всё (например, при обновлении списка, чтобы подхватить изменённые файлы). */
    static void clear() {
        for (TexUtil.Tex t : LOADED.values()) TexUtil.release(t);
        LOADED.clear();
        LOADING.clear();
        FAILED.clear();
    }
}
