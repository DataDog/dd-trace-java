package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_POOL_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.pool.HikariPool;
import com.zaxxer.hikari.util.ConcurrentBag;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;

/**
 * Instrument Hikari's ConcurrentBag class to detect when blocking occurs trying to get an entry
 * from the connection pool.
 */
@AutoService(InstrumenterModule.class)
public final class HikariConcurrentBagInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HikariConcurrentBagInstrumentation() {
    super("jdbc-datasource");
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.util.ConcurrentBag";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HikariBlockedTrackingSynchronousQueue", packageName + ".HikariBlockedTracker"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    // For getting the poolName
    return singletonMap("com.zaxxer.hikari.util.ConcurrentBag", String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), HikariConcurrentBagInstrumentation.class.getName() + "$ConstructorAdvice");
    transformer.applyAdvice(
        named("borrow"), HikariConcurrentBagInstrumentation.class.getName() + "$BorrowAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.This ConcurrentBag<?> thiz)
        throws IllegalAccessException, NoSuchFieldException {
      try {
        Field handoffQueueField = thiz.getClass().getDeclaredField("handoffQueue");
        handoffQueueField.setAccessible(true);
        handoffQueueField.set(thiz, new HikariBlockedTrackingSynchronousQueue<>());
      } catch (NoSuchFieldException e) {
        // ignore -- see HikariQueuedSequenceSynchronizerInstrumentation for older Hikari versions
      }

      Field hikariPoolField = thiz.getClass().getDeclaredField("listener");
      hikariPoolField.setAccessible(true);
      HikariPool hikariPool = (HikariPool) hikariPoolField.get(thiz);

      /*
       * In earlier versions of Hikari, poolName is directly inside HikariPool, and
       * in later versions it is in the PoolBase superclass.
       */
      final Class<?> hikariPoolSuper = hikariPool.getClass().getSuperclass();
      final Class<?> poolNameContainingClass;
      if (!hikariPoolSuper.getName().equals("java.lang.Object")) {
        poolNameContainingClass = hikariPoolSuper;
      } else {
        poolNameContainingClass = hikariPool.getClass();
      }
      Field poolNameField = poolNameContainingClass.getDeclaredField("poolName");
      poolNameField.setAccessible(true);
      String poolName = (String) poolNameField.get(hikariPool);
      InstrumentationContext.get(ConcurrentBag.class, String.class).put(thiz, poolName);
    }
  }

  public static class BorrowAdvice {
    private static final String POOL_WAITING = "pool.waiting";

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
            startSpan("hikari", POOL_WAITING, TimeUnit.MILLISECONDS.toMicros(startTimeMillis));
        final String poolName =
            InstrumentationContext.get(ConcurrentBag.class, String.class).get(thiz);
        if (poolName != null) {
          span.setTag(DB_POOL_NAME, poolName);
        }
        // XXX should we do anything with the throwable?
        span.finish();
      }
      HikariBlockedTracker.clearBlocked();
    }
  }
}
