package datadog.trace.instrumentation.jdbc;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.util.ConcurrentBag;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Store the poolName associated with a {@link ConcurrentBag} for later use in {@link
 * HikariConcurrentBagInstrumentation}.
 */
@AutoService(InstrumenterModule.class)
public final class HikariPoolInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HikariPoolInstrumentation() {
    super("jdbc", "hikari");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isJdbcPoolWaitingEnabled();
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.pool.HikariPool";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("com.zaxxer.hikari.util.ConcurrentBag", String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), HikariPoolInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.FieldValue("connectionBag") ConcurrentBag concurrentBag,
        @Advice.FieldValue("poolName") String poolName) {
      InstrumentationContext.get(ConcurrentBag.class, String.class).put(concurrentBag, poolName);
    }
  }
}
