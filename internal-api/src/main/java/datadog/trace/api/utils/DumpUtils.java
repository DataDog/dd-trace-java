package datadog.trace.api.utils;

import java.util.Map;

public abstract class DumpUtils {

  public static void threadDump() {
    System.out.println("========");
    for (final Map.Entry<Thread, StackTraceElement[]> t : Thread.getAllStackTraces().entrySet()) {
      System.out.println(t.getKey() + " " + t.getKey().getState());
      for (final StackTraceElement f : t.getValue()) {
        System.out.println(" at " + f);
      }
      System.out.println("--------");
    }
    System.out.println("========");
  }

  public static Thread scheduleThreadDump(final long periodMillis) {

    final Thread dumpThread =
        new Thread() {
          @Override
          public void run() {
            try {
              while (!Thread.interrupted()) {
                threadDump();
                Thread.sleep(periodMillis);
              }
            } catch (final InterruptedException e) {
              // done
            }
          }
        };

    dumpThread.setDaemon(true);
    dumpThread.start();

    return dumpThread;
  }
}
