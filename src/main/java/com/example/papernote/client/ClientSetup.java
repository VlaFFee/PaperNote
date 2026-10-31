package com.example.papernote.client;

import com.example.papernote.PaperNoteMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ClientSetup {
    private ClientSetup() {}

    /** Запуск игры: создаём папку для фото и проверяем, есть ли в ней картинки. */
    @Mod.EventBusSubscriber(modid = PaperNoteMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                LocalPhotos.init();
                PhotoCache.init();
            });
        }
    }

    /** Выход из мира: освобождаем текстуры и память. */
    @Mod.EventBusSubscriber(modid = PaperNoteMod.MODID, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            PhotoCache.clear();
            Thumbs.clear();
        }
    }
}
