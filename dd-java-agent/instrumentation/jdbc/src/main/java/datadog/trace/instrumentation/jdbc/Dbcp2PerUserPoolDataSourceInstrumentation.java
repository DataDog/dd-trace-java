package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class Dbcp2PerUserPoolDataSourceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public Dbcp2PerUserPoolDataSourceInstrumentation() {
    super("jdbc");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isJdbcPoolWaitingEnabled();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.commons.dbcp2.datasources.PerUserPoolDataSource", // standalone
      "org.apache.tomcat.dbcp.dbcp2.datasources.PerUserPoolDataSource" // bundled with Tomcat
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getPooledConnectionAndInfo"),
        Dbcp2PerUserPoolDataSourceInstrumentation.class.getName()
            + "$GetPooledConnectionAndInfoAdvice");
  }

  public static class GetPooledConnectionAndInfoAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      CallDepthThreadLocalMap.incrementCallDepth(Dbcp2LinkedBlockingDequeInstrumentation.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
      CallDepthThreadLocalMap.decrementCallDepth(Dbcp2LinkedBlockingDequeInstrumentation.class);
    }
  }
}
