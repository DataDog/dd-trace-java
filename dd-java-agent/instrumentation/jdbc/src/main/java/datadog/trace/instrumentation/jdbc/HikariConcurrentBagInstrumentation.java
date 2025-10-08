package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_POOL_NAME;
import static datadog.trace.instrumentation.jdbc.PoolWaitingDecorator.DECORATE;
import static datadog.trace.instrumentation.jdbc.PoolWaitingDecorator.POOL_WAITING;
import static java.util.Collections.singletonMap;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.util.ConcurrentBag;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;

/**
 * Instrument Hikari's ConcurrentBag class to detect when blocking occurs trying to get an entry
 * from the connection pool. There are two related instrumentations to detect blocking for different
 * versions of Hikari. The {@link HikariBlockedTracker} contextStore is used to pass blocking state
 * from the other instrumentations to this class.
 *
 * <ul>
 *   <li>Before commit f0b3c520c (2.4.0 <= version < 2.6.0): calls to <code>
 *       synchronizer.waitUntilSequenceExceeded(startSeq, timeout)</code> with {@link
 *       HikariQueuedSequenceSynchronizerInstrumentation}
 *   <li>Commit f0b3c520c and later (version >= 2.6.0): calls to <code>
 *       handoffQueue.poll(timeout, NANOSECONDS)</code> with {@link
 *       HikariQueuedSequenceSynchronizerInstrumentation}
 * </ul>
 */
@AutoService(InstrumenterModule.class)
public final class HikariConcurrentBagInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HikariConcurrentBagInstrumentation() {
    super("jdbc", "hikari");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isJdbcPoolWaitingEnabled();
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.util.ConcurrentBag";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HikariBlockedTracker", packageName + ".PoolWaitingDecorator"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    // The contextStore Map is populated by HikariPoolInstrumentation
    return singletonMap("com.zaxxer.hikari.util.ConcurrentBag", String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("borrow"), HikariConcurrentBagInstrumentation.class.getName() + "$BorrowAdvice");
  }

  /**
   * Instead of always starting and ending a span, a pool.waiting span is only created if blocking
   * is detected when attempting to get a connection from the pool.
   */
  public static class BorrowAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Long onEnter() {
      HikariBlockedTracker.clearBlocked();
      return System.currentTimeMillis();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.This ConcurrentBag thiz,
        @Advice.Enter final Long startTimeMillis,
        @Advice.Thrown final Throwable throwable) {
      if (HikariBlockedTracker.wasBlocked()) {
        final AgentSpan span =
            startSpan(POOL_WAITING, TimeUnit.MILLISECONDS.toMicros(startTimeMillis));
        DECORATE.afterStart(span);
        DECORATE.onError(span, throwable);
        span.setResourceName("hikari.waiting");
        final String poolName =
            InstrumentationContext.get(ConcurrentBag.class, String.class).get(thiz);
        if (poolName != null) {
          span.setTag(DB_POOL_NAME, poolName);
        }

        span.finish();

        HikariBlockedTracker.clearBlocked();
      }
    }
  }
}
