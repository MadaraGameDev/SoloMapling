package soloMapling.server;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/*
    // Sample 1 Liner
    MethodScheduler.runAfterDelay(() -> methodName(args), 2500);
*/


public class MethodScheduler {
    private static final AtomicLong threadCounter = new AtomicLong(0);

    private static final ThreadFactory threadFactory = r -> {
        Thread thread = new Thread(r);
        thread.setName("SchedulerThread-" + threadCounter.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    };

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors(),
            threadFactory
    );

    public static void runAfterDelay(Runnable method, long delayMilliseconds) {
        scheduler.schedule(() -> {
            try {
                method.run();
            } catch (Exception e) {
                System.out.println("runAfterDelay catch exception: " + method.toString());
                // Log the exception
                e.printStackTrace();
            }
        }, delayMilliseconds, TimeUnit.MILLISECONDS);
    }

    public static void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    // Optional: method to check if tasks are piling up
    public static long getPendingTaskCount() {
        return ((ThreadPoolExecutor) scheduler).getQueue().size();
    }
}
