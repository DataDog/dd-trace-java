package datadog.smoketest.crashtracking;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class CrashtrackingTestApplication {
  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      throw new RuntimeException("Expecting the control file as an argument");
    }
    System.out.println("=== CrashtrackingTestApplication ===");
    CountDownLatch latch = new CountDownLatch(1);

    Thread t =
        new Thread(
            () -> {
              Path scriptPath = Paths.get(args[0]);
              while (!Files.exists(scriptPath)) {
                System.out.println("Waiting for the script " + scriptPath + " to be created...");
                LockSupport.parkNanos(1_000_000_000L);
              }
              latch.countDown();
            });
    t.setDaemon(true);
    t.start();

    System.out.println("Waiting for initialization...");
    latch.await(5, TimeUnit.MINUTES);

    // let's provoke OOME
    List<byte[]> buffer = new ArrayList<>();
    int size = 1;
    while (size < 1024) {
      buffer.add(new byte[size * 1024 * 1024]);
      System.out.println("Allocated " + size + "MB");
      if (size < 512) {
        size *= 2;
      }
    }
    System.out.println(buffer.size());
  }
}
