package com.example.papernote.net;

import com.example.papernote.client.PhotoCache;
import com.example.papernote.server.NoteServer;
import com.example.papernote.server.PhotoStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class Packets {
    private Packets() {}

    // ------------------------------------------------------ клиент → сервер

    /** Сохранить текст и фото в записку в руке (только творческий режим). */
    public static final class Save {
        public final int hand;
        public final String text;
        public final String photo;

        public Save(int hand, String text, String photo) {
            this.hand = hand;
            this.text = text;
            this.photo = photo;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(hand);
            buf.writeUtf(text, 4000);
            buf.writeUtf(photo, 64);
        }

        public static Save decode(FriendlyByteBuf buf) {
            return new Save(buf.readVarInt(), buf.readUtf(4000), buf.readUtf(64));
        }

        public void handle(Supplier<NetworkEvent.Context> sup) {
            NetworkEvent.Context ctx = sup.get();
            ctx.enqueueWork(() -> NoteServer.save(ctx.getSender(), this));
            ctx.setPacketHandled(true);
        }
    }

    /** Кусок загружаемого фото (JPEG). */
    public static final class UploadChunk {
        public final String hash;
        public final int index;
        public final int total;
        public final byte[] data;

        public UploadChunk(String hash, int index, int total, byte[] data) {
            this.hash = hash;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(hash, 64);
            buf.writeVarInt(index);
            buf.writeVarInt(total);
            buf.writeByteArray(data);
        }

        public static UploadChunk decode(FriendlyByteBuf buf) {
            return new UploadChunk(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(),
                    buf.readByteArray(Net.CHUNK_SIZE + 16));
        }

        public void handle(Supplier<NetworkEvent.Context> sup) {
            NetworkEvent.Context ctx = sup.get();
            ctx.enqueueWork(() -> PhotoStore.onUploadChunk(ctx.getSender(), this));
            ctx.setPacketHandled(true);
        }
    }

    /** Клиент просит у сервера фото по хэшу. */
    public static final class PhotoRequest {
        public final String hash;

        public PhotoRequest(String hash) {
            this.hash = hash;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(hash, 64);
        }

        public static PhotoRequest decode(FriendlyByteBuf buf) {
            return new PhotoRequest(buf.readUtf(64));
        }

        public void handle(Supplier<NetworkEvent.Context> sup) {
            NetworkEvent.Context ctx = sup.get();
            ctx.enqueueWork(() -> PhotoStore.onRequest(ctx.getSender(), hash));
            ctx.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------ сервер → клиент

    /** Кусок фото для клиента. total == 0 означает «такого фото на сервере нет». */
    public static final class PhotoChunk {
        public final String hash;
        public final int index;
        public final int total;
        public final byte[] data;

        public PhotoChunk(String hash, int index, int total, byte[] data) {
            this.hash = hash;
            this.index = index;
            this.total = total;
            this.data = data;
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUtf(hash, 64);
            buf.writeVarInt(index);
            buf.writeVarInt(total);
            buf.writeByteArray(data);
        }

        public static PhotoChunk decode(FriendlyByteBuf buf) {
            return new PhotoChunk(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(),
                    buf.readByteArray(Net.CHUNK_SIZE + 16));
        }

        public void handle(Supplier<NetworkEvent.Context> sup) {
            NetworkEvent.Context ctx = sup.get();
            ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> PhotoCache.onChunk(hash, index, total, data)));
            ctx.setPacketHandled(true);
        }
    }
}
