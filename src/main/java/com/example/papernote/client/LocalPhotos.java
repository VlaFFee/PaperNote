package com.example.papernote.client;

import com.example.papernote.PaperNoteMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Папка с фотографиями игрока: &lt;папка игры&gt;/papernote_photos. */
public final class LocalPhotos {
    public record Entry(Path path, String name, long size, long modified) {}

    public static final Path DIR = FMLPaths.GAMEDIR.get().resolve("papernote_photos");
    private static final Set<String> EXT = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final int MAX_ENTRIES = 1000;

    private LocalPhotos() {}

    /** Создаёт папку (если её нет), кладёт подсказку и проверяет, есть ли в ней фото. Вызывается при запуске игры. */
    public static void init() {
        try {
            Files.createDirectories(DIR);
            Path readme = DIR.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                        Paper Note: папка для фотографий / photo folder
                        ------------------------------------------------
                        RU: Положите сюда картинки (png, jpg, jpeg, gif, bmp), затем в творческом режиме
                            откройте записку (ПКМ) и нажмите «Фото». Можно просто перетащить файл в окно игры.
                        EN: Put images (png, jpg, jpeg, gif, bmp) here, then in creative mode open a note
                            (right click) and press "Photo". You can also drag a file into the game window.
                        """, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            PaperNoteMod.LOGGER.error("[PaperNote] Could not create photo folder {}", DIR, e);
            return;
        }
        List<Entry> found = scan();
        if (found.isEmpty()) {
            PaperNoteMod.LOGGER.info("[PaperNote] Photo folder is ready but empty: {}", DIR);
        } else {
            PaperNoteMod.LOGGER.info("[PaperNote] Photo folder: {} — found {} image(s)", DIR, found.size());
        }
    }

    public static boolean isImage(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot > 0 && EXT.contains(n.substring(dot + 1));
    }

    /** Блокирующее сканирование (только верхний уровень папки), новые файлы первыми. */
    public static List<Entry> scan() {
        try {
            Files.createDirectories(DIR);
            try (Stream<Path> s = Files.list(DIR)) {
                return s.filter(Files::isRegularFile)
                        .filter(LocalPhotos::isImage)
                        .map(p -> {
                            long size = 0, mod = 0;
                            try {
                                size = Files.size(p);
                                mod = Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException ignored) {
                            }
                            return new Entry(p, p.getFileName().toString(), size, mod);
                        })
                        .sorted(Comparator.comparingLong(Entry::modified).reversed())
                        .limit(MAX_ENTRIES)
                        .toList();
            }
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Сканирует в фоне, результат отдаёт в главном потоке. */
    public static void scanAsync(Consumer<List<Entry>> done) {
        Async.IO.execute(() -> {
            List<Entry> list = scan();
            Minecraft.getInstance().execute(() -> done.accept(list));
        });
    }

    /** Копирует файл в папку фото (если он не оттуда) и возвращает путь копии. */
    public static Path importFile(Path src) throws IOException {
        Files.createDirectories(DIR);
        Path a = src.toAbsolutePath().normalize();
        if (a.startsWith(DIR.toAbsolutePath().normalize())) return src;
        String name = src.getFileName().toString();
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        Path target = DIR.resolve(name);
        for (int i = 1; Files.exists(target) && i < 1000; i++) {
            target = DIR.resolve(base + "_" + i + ext);
        }
        Files.copy(src, target);
        return target;
    }
}
