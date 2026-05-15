package com.ephemeral.android;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppExecutors {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor main = command -> mainHandler.post(command);
    private final ExecutorService network = bounded("network", 3, 32);
    private final ExecutorService disk = bounded("disk", 1, 16);
    private final ExecutorService image = bounded("image", 2, 24);
    private final ExecutorService compute = bounded("compute", 2, 24);

    public Executor main() {
        return main;
    }

    public ExecutorService network() {
        return network;
    }

    public ExecutorService disk() {
        return disk;
    }

    public ExecutorService image() {
        return image;
    }

    public ExecutorService compute() {
        return compute;
    }

    public void shutdown() {
        network.shutdownNow();
        disk.shutdownNow();
        image.shutdownNow();
        compute.shutdownNow();
    }

    private static ExecutorService bounded(String name, int threads, int queueSize) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueSize),
                new NamedThreadFactory("ephemeral-" + name),
                new ThreadPoolExecutor.DiscardPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger nextId = new AtomicInteger(1);

        NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + nextId.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
