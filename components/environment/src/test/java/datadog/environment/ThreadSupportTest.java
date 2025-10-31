package datadog.environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.condition.JRE.JAVA_21;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;

class ThreadSupportTest {
  private static ExecutorService singleThreadExecutor;
  private static ExecutorService newVirtualThreadPerTaskExecutor;

  @BeforeAll
  static void beforeAll() {
    singleThreadExecutor = Executors.newSingleThreadExecutor();
    newVirtualThreadPerTaskExecutor = ThreadSupport.newVirtualThreadPerTaskExecutor().orElse(null);
  }

  @Test
  public void testThreadId() throws InterruptedException {
    AtomicLong threadId = new AtomicLong();
    Thread thread = new Thread(() -> threadId.set(ThreadSupport.threadId()), "foo");
    thread.start();
    try {
      // always works on Thread's where getId isn't overridden by child class
      assertEquals(thread.getId(), ThreadSupport.threadId(thread));
    } finally {
      thread.join();
    }
    assertEquals(thread.getId(), threadId.get());
  }

  @Test
  void testSupportsVirtualThreads() {
    assertEquals(
        JavaVirtualMachine.isJavaVersionAtLeast(21),
        ThreadSupport.supportsVirtualThreads(),
        "expected virtual threads support status");
  }

  @Test
  void testPlatformThread() {
    assertVirtualThread(singleThreadExecutor, false);
  }

  @Test
  @EnabledOnJre(JAVA_21)
  void testVirtualThread() {
    assertVirtualThread(newVirtualThreadPerTaskExecutor, true);
  }

  static void assertVirtualThread(ExecutorService executorService, boolean expected) {
    Future<Boolean> futureCurrent = executorService.submit(() -> ThreadSupport.isVirtual());
    Future<Boolean> futureGiven =
        executorService.submit(
            () -> {
              Thread thread = Thread.currentThread();
              return ThreadSupport.isVirtual(thread);
            });
    try {
      assertEquals(expected, futureCurrent.get(), "invalid current thread virtual status");
      assertEquals(expected, futureGiven.get(), "invalid given thread virtual status");
    } catch (Throwable e) {
      fail("Can't get thread virtual status", e);
    }
  }

  @AfterAll
  static void afterAll() {
    singleThreadExecutor.shutdown();
    if (newVirtualThreadPerTaskExecutor != null) {
      newVirtualThreadPerTaskExecutor.shutdown();
    }
  }
}
