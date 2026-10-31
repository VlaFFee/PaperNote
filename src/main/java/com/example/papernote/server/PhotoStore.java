package com.example.papernote.server;

import com.example.papernote.PaperNoteMod;
import com.example.papernote.image.ImageUtil;
import com.example.papernote.net.Net;
import com.example.papernote.net.Packets;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Серверное хранилище фото: &lt;мир&gt;/papernote_photos/&lt;sha1&gt;.jpg
 * Все входящие данные проверяются: права, размер, число кусков, хэш, формат, габариты.
 */
public final class PhotoStore {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_DIM = 512;
    public static final int MAX_CHUNKS = 12;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");
    private static final long MIN_REQUEST_INTERVAL_MS = 150L;

    private static final class Upload {
        final String hash;
        final int total;
        int next;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Upload(String hash, int total) {
            this.hash = hash;
            this.total = total;
        }
    }

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    private PhotoStore() {}

    public static boolean validHash(String hash) {
        return hash != null && HASH.matcher(hash).matches();
    }

    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("papernote_photos");
    }

    public static boolean exists(MinecraftServer server, String hash) {
        return server != null && validHash(hash) && Files.isRegularFile(dir(server).resolve(hash + ".jpg"));
    }

    public static void forget(UUID id) {
        UPLOADS.remove(id);
        LAST_REQUEST.remove(id);
    }

    // ------------------------------------------------------------- загрузка

    public static void onUploadChunk(ServerPlayer player, Packets.UploadChunk pk) {
        if (player == null) return;
        UUID id = player.getUUID();
        MinecraftServer server = player.getServer();
        if (server == null) return;

        // Загружать фото может только игрок в творческом режиме
        if (!player.isCreative()) {
            UPLOADS.remove(id);
            return;
        }
        if (!validHash(pk.hash) || pk.total < 1 || pk.total > MAX_CHUNKS
                || pk.index < 0 || pk.index >= pk.total
                || pk.data.length == 0 || pk.data.length > Net.CHUNK_SIZE) {
            UPLOADS.remove(id);
            return;
        }
        if (exists(server, pk.hash)) { // уже есть на сервере, повторно не принимаем
            UPLOADS.remove(id);
            return;
        }

        Upload up = UPLOADS.get(id);
        if (pk.index == 0) {
            up = new Upload(pk.hash, pk.total);
            UPLOADS.put(id, up);
        } else if (up == null || !up.hash.equals(pk.hash) || up.total != pk.total || up.next != pk.index) {
            UPLOADS.remove(id);
            return;
        }
        if (up.out.size() + pk.data.length > MAX_BYTES) {
            UPLOADS.remove(id);
            return;
        }
        up.out.writeBytes(pk.data);
        up.next++;

        if (up.next == up.total) {
            UPLOADS.remove(id);
            finish(server, player, up);
        }
    }

    private static void finish(MinecraftServer server, ServerPlayer player, Upload up) {
        byte[] bytes = up.out.toByteArray();
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8 || (bytes[2] & 0xFF) != 0xFF) {
            PaperNoteMod.LOGGER.warn("[PaperNote] {} sent a non-JPEG photo, ignored", player.getGameProfile().getName());
            return;
        }
        if (!ImageUtil.sha1(bytes).equals(up.hash)) {
            PaperNoteMod.LOGGER.warn("[PaperNote] {} sent a photo with a wrong hash, ignored", player.getGameProfile().getName());
            return;
        }
        try {
            int[] dim = ImageUtil.dimensions(bytes);
            if (dim == null || dim[0] < 1 || dim[1] < 1 || dim[0] > MAX_DIM || dim[1] > MAX_DIM) {
                PaperNoteMod.LOGGER.warn("[PaperNote] {} sent an invalid photo, ignored", player.getGameProfile().getName());
                return;
            }
        } catch (NoClassDefFoundError e) {
            // На урезанной JRE без java.desktop проверку габаритов пропускаем: остальные проверки уже пройдены
        }
        try {
            Path dir = dir(server);
            Files.createDirectories(dir);
            Path tmp = dir.resolve(up.hash + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dir.resolve(up.hash + ".jpg"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            PaperNoteMod.LOGGER.error("[PaperNote] Could not store photo {}", up.hash, e);
        }
    }

    // ----------------------------------------------------------------- выдача

    public static void onRequest(ServerPlayer player, String hash) {
        if (player == null || !validHash(hash)) return;
        long now = Util.getMillis();
        Long last = LAST_REQUEST.get(player.getUUID());
        if (last != null && now - last < MIN_REQUEST_INTERVAL_MS) return; // защита от спама
        LAST_REQUEST.put(player.getUUID(), now);

        MinecraftServer server = player.getServer();
        if (server == null) return;
        Path file = dir(server).resolve(hash + ".jpg");

        Util.backgroundExecutor().execute(() -> {
            byte[] data = null;
            try {
                if (Files.isRegularFile(file) && Files.size(file) <= MAX_BYTES) {
                    data = Files.readAllBytes(file);
                }
            } catch (IOException ignored) {
            }
            if (data == null || data.length == 0) {
                send(player, new Packets.PhotoChunk(hash, 0, 0, new byte[0]));
                return;
            }
            int total = (data.length + Net.CHUNK_SIZE - 1) / Net.CHUNK_SIZE;
            for (int i = 0; i < total; i++) {
                int from = i * Net.CHUNK_SIZE;
                int to = Math.min(data.length, from + Net.CHUNK_SIZE);
                send(player, new Packets.PhotoChunk(hash, i, total, Arrays.copyOfRange(data, from, to)));
            }
        });
    }

    private static void send(ServerPlayer player, Packets.PhotoChunk pk) {
        Net.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), pk);
    }
}
