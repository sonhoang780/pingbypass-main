package eu.client.utils.system;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ThreadExecutor {
    // Callers (module onPlayerUpdate handlers, mostly) call execute() once per tick with no
    // regard for whether their previous call is still running -- that used to spawn a brand new
    // raw Thread every single time, unthrottled, with nothing to cancel it on module disable.
    // Under any hiccup (GC pause, a slow tick) that piles up dozens of concurrent threads racing
    // to overwrite the same shared fields in unpredictable completion order, which is exactly
    // what made things feel "sometimes fast, sometimes slow" for no visible reason. Key the
    // in-flight state per caller (its Class) and skip starting another until the previous one
    // finishes -- only the latest result is ever useful anyway.
    private static final Map<Class<?>, Thread> RUNNING = new ConcurrentHashMap<>();

    public static void execute(Runnable runnable) {
        Class<?> caller = runnable.getClass().getEnclosingClass();
        if (caller == null) caller = runnable.getClass();

        Thread running = RUNNING.get(caller);
        if (running != null && running.isAlive()) return;

        final Class<?> key = caller;
        Thread thread = new OneTimeThread(() -> {
            try {
                runnable.run();
            } finally {
                RUNNING.remove(key);
            }
        });
        RUNNING.put(key, thread);
        thread.start();
    }

    private static class OneTimeThread extends Thread {
        private final Runnable runnable;

        public OneTimeThread(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public void run() {
            runnable.run();
        }
    }
}
