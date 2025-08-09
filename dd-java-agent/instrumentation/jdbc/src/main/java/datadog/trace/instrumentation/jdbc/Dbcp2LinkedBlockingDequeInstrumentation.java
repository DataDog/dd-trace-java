package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class Dbcp2LinkedBlockingDequeInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public Dbcp2LinkedBlockingDequeInstrumentation() {
    super("jdbc-datasource");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.commons.pool2.impl.LinkedBlockingDeque", // standalone
      "org.apache.tomcat.dbcp.pool2.impl.LinkedBlockingDeque" // bundled with Tomcat
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("pollFirst").and(takesArguments(1)),
        Dbcp2LinkedBlockingDequeInstrumentation.class.getName() + "$PollFirstAdvice");
  }

  public static class PollFirstAdvice {
    private static final String POOL_WAITING = "pool.waiting";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan onEnter() {
      if (CallDepthThreadLocalMap.getCallDepth(Dbcp2LinkedBlockingDequeInstrumentation.class) > 0) {
        return startSpan("dbcp2", POOL_WAITING);
      } else {
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final AgentSpan span) {
      if (span != null) {
        span.finish();
      }
    }
  }
}
