package datadog.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class ThreadUtilsTest {
  @Test
  public void threadId() throws InterruptedException {
    Thread thread = new Thread("foo");
    thread.start();
    try {
      // always works on Thread's where getId isn't overridden by child class
      assertEquals(thread.getId(), ThreadUtils.threadId(thread));
    } finally {
      thread.join();
    }
  }

  @Test
  public void supportsVirtualThreads() {
    assertEquals(
        JavaVersion.getRuntimeVersion().isAtLeast(21), ThreadUtils.supportsVirtualThreads());
  }

  @Test
  public void isVirtualThread_false() throws InterruptedException {
    Thread thread = new Thread("foo");
    thread.start();
    try {
      assertFalse(ThreadUtils.isVirtual(thread));
    } finally {
      thread.join();
    }
  }

  @Test
  public void isCurrentThreadVirtual_false() throws InterruptedException, ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      assertFalse(executor.submit(() -> ThreadUtils.isCurrentThreadVirtual()).get());
    } finally {
      executor.shutdown();
    }
  }

  @Test
  public void isVirtualThread_true() throws InterruptedException {
    assumeTrue(ThreadUtils.supportsVirtualThreads());

    Thread vThread = startVirtualThread(() -> {});
    try {
      assertTrue(ThreadUtils.isVirtual(vThread));
    } finally {
      vThread.join();
    }
  }

  @Test
  public void isCurrentThreadVirtual_true() throws InterruptedException {
    assumeTrue(ThreadUtils.supportsVirtualThreads());

    AtomicBoolean result = new AtomicBoolean();

    Thread vThread =
        startVirtualThread(
            () -> {
              result.set(ThreadUtils.isCurrentThreadVirtual());
            });

    vThread.join();
    assertTrue(result.get());
  }

  /*
   * Should only be called on JVMs that support virtual threads
   */
  static final Thread startVirtualThread(Runnable runnable) {
    MethodHandle h_startVThread;
    try {
      h_startVThread =
          MethodHandles.lookup()
              .findStatic(
                  Thread.class,
                  "startVirtualThread",
                  MethodType.methodType(Thread.class, Runnable.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new IllegalStateException(e);
    }

    try {
      return (Thread) h_startVThread.invoke(runnable);
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }
}
