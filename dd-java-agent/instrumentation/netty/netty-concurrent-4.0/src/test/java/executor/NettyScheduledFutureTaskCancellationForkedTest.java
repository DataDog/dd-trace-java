package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import executor.NettyScheduledFutureTaskContextPropagationTest.CloseableDefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import org.tabletest.junit.TableTest;

/** Installs the pause before Netty loads so prior test transformations cannot bypass it. */
class NettyScheduledFutureTaskCancellationForkedTest extends AbstractInstrumentationTest {
  @TableTest({
    "Scenario  | scheduled",
    "submitted | false    ",
    "scheduled | true     "
  })
  void testSuccessfulCancellationBeforeExecutionDoesNotActivateContext(boolean scheduled)
      throws Exception {
    Instrumentation instrumentation = ByteBuddyAgent.getInstrumentation();
    ResettableClassFileTransformer gateTransformer =
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .type(named("io.netty.util.concurrent.PromiseTask"))
            .transform(
                (builder, type, loader, module, domain) ->
                    builder.visit(
                        Advice.to(NettyCancellationGate.PauseAdvice.class)
                            .on(named("setUncancellableInternal"))))
            .installOn(instrumentation);
    NettyCancellationGate.entered = new CountDownLatch(1);
    NettyCancellationGate.proceed = new CountDownLatch(1);
    NettyCancellationGate.activeSpanAtGate = null;
    try (CloseableDefaultEventExecutorGroup group = new CloseableDefaultEventExecutorGroup()) {
      EventExecutor executor = group.next();
      CountDownLatch workerBlocked = new CountDownLatch(1);
      CountDownLatch workerProceed = new CountDownLatch(1);
      executor.execute(
          () -> {
            workerBlocked.countDown();
            try {
              assertTrue(workerProceed.await(5, SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError(e);
            }
          });
      assertTrue(workerBlocked.await(5, SECONDS));
      AtomicBoolean called = new AtomicBoolean();
      AgentSpan parent = startSpan("test", "parent");
      Future<?> future;
      try (ContextScope ignored = activateSpan(parent)) {
        Callable<Object> callable =
            () -> {
              called.set(true);
              return null;
            };
        future =
            scheduled ? executor.schedule(callable, 0, MILLISECONDS) : executor.submit(callable);
      } finally {
        parent.finish();
      }
      NettyCancellationGate.task = future;
      // Publish the task identity before allowing the worker to reach the gate.
      workerProceed.countDown();
      try {
        assertTrue(NettyCancellationGate.entered.await(5, SECONDS));
        assertTrue(future.cancel(false));
      } finally {
        NettyCancellationGate.proceed.countDown();
      }
      executor.submit(() -> {}).get(5, SECONDS);
      assertFalse(called.get());
      assertNull(NettyCancellationGate.activeSpanAtGate);
      assertTraces(trace(span().root().operationName("parent")));
    } finally {
      NettyCancellationGate.proceed.countDown();
      NettyCancellationGate.task = null;
      NettyCancellationGate.activeSpanAtGate = null;
      assertTrue(
          gateTransformer.reset(
              instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION));
    }
  }
}
