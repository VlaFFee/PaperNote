package com.example.papernote.net;

import com.example.papernote.PaperNoteMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Arrays;

public final class Net {
    private static final String PROTOCOL = "1";

    /** Размер одного куска фото. Лимит пакета клиент→сервер ≈ 32 КБ, берём с запасом. */
    public static final int CHUNK_SIZE = 28000;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(PaperNoteMod.MODID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private Net() {}

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, Packets.Save.class, Packets.Save::encode, Packets.Save::decode, Packets.Save::handle);
        CHANNEL.registerMessage(id++, Packets.UploadChunk.class, Packets.UploadChunk::encode, Packets.UploadChunk::decode, Packets.UploadChunk::handle);
        CHANNEL.registerMessage(id++, Packets.PhotoRequest.class, Packets.PhotoRequest::encode, Packets.PhotoRequest::decode, Packets.PhotoRequest::handle);
        CHANNEL.registerMessage(id++, Packets.PhotoChunk.class, Packets.PhotoChunk::encode, Packets.PhotoChunk::decode, Packets.PhotoChunk::handle);
    }

    /** Клиент → сервер: отправить готовый JPEG кусками. */
    public static void sendUpload(String hash, byte[] data) {
        int total = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(data.length, from + CHUNK_SIZE);
            CHANNEL.sendToServer(new Packets.UploadChunk(hash, i, total, Arrays.copyOfRange(data, from, to)));
        }
    }
}
