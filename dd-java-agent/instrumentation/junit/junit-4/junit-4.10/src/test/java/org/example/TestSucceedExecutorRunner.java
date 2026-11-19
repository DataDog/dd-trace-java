package org.example;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import org.junit.runner.RunWith;
import org.junit.runner.notification.RunNotifier;

@RunWith(TestSucceedExecutorRunner.ExecutorRunner.class)
public class TestSucceedExecutorRunner {

  public static ExecutorService executor;

  public static class ExecutorRunner extends TestSucceedCustomRunner.CustomRunner {
    public ExecutorRunner(Class<?> testClass) {
      super(testClass);
    }

    @Override
    public void run(RunNotifier notifier) {
      CompletableFuture.runAsync(() -> emitTestEvents(notifier), executor).join();
    }
  }
}
