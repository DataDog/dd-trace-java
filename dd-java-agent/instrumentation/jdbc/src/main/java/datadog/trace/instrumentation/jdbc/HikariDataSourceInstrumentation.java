package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import com.google.auto.service.AutoService;
import com.zaxxer.hikari.HikariDataSource;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class HikariDataSourceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType {

  public HikariDataSourceInstrumentation() {
    super("jdbc-datasource");
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.HikariDataSource";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getConnection"),
        HikariDataSourceInstrumentation.class.getName() + "$HikariGetConnectionAdvice");
  }

  public static class HikariGetConnectionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void start(@Advice.This final HikariDataSource ds) {
      if (activeSpan() == null) {
        // Don't want to generate a new top-level span
        return;
      }
      final AgentSpan span = activeSpan();

      String hikariPoolname = ds.getPoolName();
      span.setTag("hikari.poolname", hikariPoolname);
    }
  }
}
