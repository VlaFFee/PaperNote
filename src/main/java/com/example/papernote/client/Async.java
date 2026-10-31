package com.example.papernote.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Один небольшой пул для тяжёлой работы с картинками, чтобы не нагружать игру и не плодить потоки. */
final class Async {
    static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "papernote-io");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private Async() {}
}
