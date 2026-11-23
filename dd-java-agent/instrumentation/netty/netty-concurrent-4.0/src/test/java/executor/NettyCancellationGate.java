package executor;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.concurrent.CountDownLatch;
import net.bytebuddy.asm.Advice;

/** Pauses a real Netty task immediately before its execution/cancellation decision. */
public final class NettyCancellationGate {
  public static volatile Object task;
  public static volatile Object activeSpanAtGate;
  public static volatile CountDownLatch entered;
  public static volatile CountDownLatch proceed;

  public static void awaitExecutionDecision(Object candidate) throws InterruptedException {
    if (candidate == task) {
      activeSpanAtGate = activeSpan();
      entered.countDown();
      if (!proceed.await(5, SECONDS)) {
        throw new AssertionError("Cancellation was not attempted");
      }
    }
  }

  public static final class PauseAdvice {
    @Advice.OnMethodEnter
    public static void before(@Advice.This Object task) throws InterruptedException {
      awaitExecutionDecision(task);
    }
  }
}
