package datadog.smoketest.crashtracking;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class CrashtrackingTestApplication {
  public static void main(String[] args) throws Exception {
    HotSpotDiagnosticMXBean diagBean =
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    String onError = null;
    if (diagBean == null) {
      System.err.println("HotSpotDiagnosticMXBean is not available");
      System.exit(-1);
    }

    String onOutOfMemoryError = diagBean.getVMOption("OnOutOfMemoryError").getValue();
    long ts = System.nanoTime();
    // There is a delay between the JVM startup and the moment when the OnError option is set due to
    // possible
    // dependency on JMX - let's wait for it to be set.
    while ((onError == null || onError.isEmpty()) && (System.nanoTime() - ts) < 5_000_000_000L) {
      onError = diagBean.getVMOption("OnError").getValue();
      if (onOutOfMemoryError == null) {
        onOutOfMemoryError = diagBean.getVMOption("OnOutOfMemoryError").getValue();
      }
      LockSupport.parkNanos(100_00_000L); // 100ms
    }

    if (onError == null && onOutOfMemoryError == null) {
      System.err.println("Neither OnError nor OnOutOfMemoryError is specified");
      System.exit(-1);
    }

    String crashUploaderScript =
        Arrays.stream(onError.split(";"))
            .filter(s -> s.trim().contains("dd_crash_uploader"))
            .findFirst()
            .map(s -> s.replace(" %p", ""))
            .orElse(null);
    String oomeNotifierScript =
        Arrays.stream(onOutOfMemoryError.split(";"))
            .filter(s -> s.trim().contains("dd_oome_notifier"))
            .findFirst()
            .map(s -> s.replace(" %p", ""))
            .orElse(null);
    if (crashUploaderScript == null && oomeNotifierScript == null) {
      System.err.println("Neither OnError nor OnOutOfMemoryError contains the expected value");
      System.exit(-1);
    }

    System.out.println("===> Waiting...");
    System.out.flush();

    CountDownLatch latch = new CountDownLatch(1);

    Thread t =
        new Thread(
            () -> {
              Path scriptPath =
                  Paths.get(crashUploaderScript != null ? crashUploaderScript : oomeNotifierScript);
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
