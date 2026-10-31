package com.example.papernote.client;

import com.example.papernote.PaperNoteMod;
import com.example.papernote.image.ImageUtil;
import com.example.papernote.net.Net;
import com.example.papernote.net.Packets;
import com.example.papernote.server.PhotoStore;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Клиентский кэш фото записок.
 * 1) текстуры в памяти (LRU, не более 10 штук);
 * 2) файлы на диске в &lt;папка игры&gt;/papernote_cache (общий лимит ~64 МБ, старые удаляются);
 * 3) если фото нет ни там, ни там — запрос на сервер кусками.
 * Все методы — из главного потока.
 */
public final class PhotoCache {
    public enum State {LOADED, LOADING, FAILED, NONE}

    public record Entry(net.minecraft.resources.ResourceLocation loc, int w, int h) {}

    private static final int MAX_TEXTURES = 10;
    private static final long CACHE_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final long CACHE_TRIM_TO_BYTES = 48L * 1024 * 1024;
    private static final long NET_TIMEOUT_MS = 20_000L;
    private static final long RETRY_AFTER_MS = 15_000L;

    private static final Path CACHE_DIR = FMLPaths.GAMEDIR.get().resolve("papernote_cache");

    private static final Map<String, TexUtil.Tex> LOADED = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TexUtil.Tex> eldest) {
            if (size() > MAX_TEXTURES) {
                TexUtil.release(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private static final Set<String> LOADING = new HashSet<>();
    private static final Map<String, Long> FAILED = new HashMap<>();
    private static final Map<String, Long> NET_STARTED = new HashMap<>();
    private static final Map<String, Assembly> ASSEMBLIES = new HashMap<>();

    private static final class Assembly {
        final int total;
        int next;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Assembly(int total) {
            this.total = total;
        }
    }

    private PhotoCache() {}

    // ------------------------------------------------------------------ API

    /** Вызывается при запуске клиента: создаёт папку кэша и в фоне подчищает её. */
    public static void init() {
        try {
            Files.createDirectories(CACHE_DIR);
        } catch (IOException e) {
            PaperNoteMod.LOGGER.warn("[PaperNote] Could not create cache folder", e);
            return;
        }
        Async.IO.execute(PhotoCache::trimCache);
    }

    /** Возвращает готовую текстуру или null (тогда фото грузится — спрашивать снова на следующем кадре). */
    public static Entry get(String hash) {
        TexUtil.Tex t = LOADED.get(hash);
        if (t != null) return new Entry(t.loc(), t.w(), t.h());

        long now = Util.getMillis();
        if (LOADING.contains(hash)) {
            Long started = NET_STARTED.get(hash);
            if (started != null && now - started > NET_TIMEOUT_MS) fail(hash);
            return null;
        }
        Long failedAt = FAILED.get(hash);
        if (failedAt != null) {
            if (now - failedAt < RETRY_AFTER_MS) return null;
            FAILED.remove(hash);
        }
        if (!PhotoStore.validHash(hash)) {
            FAILED.put(hash, now);
            return null;
        }
        start(hash);
        return null;
    }

    public static State state(String hash) {
        if (LOADED.containsKey(hash)) return State.LOADED;
        if (LOADING.contains(hash)) return State.LOADING;
        Long f = FAILED.get(hash);
        if (f != null && Util.getMillis() - f < RETRY_AFTER_MS) return State.FAILED;
        return State.NONE;
    }

    /** Только что подготовленное локальное фото: сразу показываем и кладём в дисковый кэш. */
    public static void putLocal(String hash, byte[] jpeg) {
        if (LOADED.containsKey(hash) || LOADING.contains(hash)) return;
        LOADING.add(hash);
        FAILED.remove(hash);
        Async.IO.execute(() -> {
            writeCache(hash, jpeg);
            decodeAndShow(hash, jpeg);
        });
    }

    /** Пришёл кусок фото от сервера. */
    public static void onChunk(String hash, int index, int total, byte[] data) {
        if (!LOADING.contains(hash)) return; // мы это не просили
        if (total == 0) { // на сервере такого фото нет
            fail(hash);
            return;
        }
        if (total < 0 || total > PhotoStore.MAX_CHUNKS || index < 0 || index >= total) {
            fail(hash);
            return;
        }
        Assembly a = ASSEMBLIES.get(hash);
        if (index == 0) {
            a = new Assembly(total);
            ASSEMBLIES.put(hash, a);
        } else if (a == null || a.total != total || a.next != index) {
            fail(hash);
            return;
        }
        if (a.out.size() + data.length > PhotoStore.MAX_BYTES) {
            fail(hash);
            return;
        }
        a.out.writeBytes(data);
        a.next++;
        if (a.next == a.total) {
            ASSEMBLIES.remove(hash);
            byte[] all = a.out.toByteArray();
            Async.IO.execute(() -> {
                if (!ImageUtil.sha1(all).equals(hash)) {
                    Minecraft.getInstance().execute(() -> fail(hash));
                    return;
                }
                writeCache(hash, all);
                decodeAndShow(hash, all);
            });
        }
    }

    /** Освободить всё (выход из мира). */
    public static void clear() {
        for (TexUtil.Tex t : LOADED.values()) TexUtil.release(t);
        LOADED.clear();
        LOADING.clear();
        FAILED.clear();
        NET_STARTED.clear();
        ASSEMBLIES.clear();
    }

    // ------------------------------------------------------------ внутреннее

    private static void start(String hash) {
        LOADING.add(hash);
        Path file = CACHE_DIR.resolve(hash + ".jpg");
        Async.IO.execute(() -> {
            if (Files.isRegularFile(file)) {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    if (ImageUtil.sha1(bytes).equals(hash)) {
                        decodeAndShow(hash, bytes);
                        return;
                    }
                    Files.deleteIfExists(file); // битый кэш
                } catch (IOException ignored) {
                }
            }
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().getConnection() == null) {
                    fail(hash);
                    return;
                }
                NET_STARTED.put(hash, Util.getMillis());
                Net.CHANNEL.sendToServer(new Packets.PhotoRequest(hash));
            });
        });
    }

    private static void decodeAndShow(String hash, byte[] jpeg) {
        ImageUtil.Decoded d = ImageUtil.read(jpeg, PhotoStore.MAX_DIM);
        Minecraft.getInstance().execute(() -> {
            if (!LOADING.contains(hash)) return; // за время загрузки кэш очистили
            LOADING.remove(hash);
            NET_STARTED.remove(hash);
            if (d == null) {
                FAILED.put(hash, Util.getMillis());
            } else {
                LOADED.put(hash, TexUtil.create(d, "photo"));
            }
        });
    }

    private static void fail(String hash) {
        LOADING.remove(hash);
        NET_STARTED.remove(hash);
        ASSEMBLIES.remove(hash);
        FAILED.put(hash, Util.getMillis());
    }

    private static void writeCache(String hash, byte[] bytes) {
        try {
            Files.createDirectories(CACHE_DIR);
            Path tmp = CACHE_DIR.resolve(hash + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, CACHE_DIR.resolve(hash + ".jpg"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // кэш — всего лишь оптимизация
        }
    }

    private static void trimCache() {
        try (Stream<Path> s = Files.list(CACHE_DIR)) {
            List<Path> files = new ArrayList<>(s.filter(Files::isRegularFile).toList());
            long total = 0;
            for (Path p : files) total += Files.size(p);
            if (total <= CACHE_LIMIT_BYTES) return;
            files.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));
            for (Path p : files) {
                if (total <= CACHE_TRIM_TO_BYTES) break;
                long size = Files.size(p);
                Files.deleteIfExists(p);
                total -= size;
            }
        } catch (IOException ignored) {
        }
    }
}
