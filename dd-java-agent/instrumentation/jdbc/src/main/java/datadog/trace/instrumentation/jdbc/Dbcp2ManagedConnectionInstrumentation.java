package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class Dbcp2ManagedConnectionInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public Dbcp2ManagedConnectionInstrumentation() {
    super("jdbc-datasource");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.commons.dbcp2.managed.ManagedConnection", // standalone
      "org.apache.tomcat.dbcp.dbcp2.managed.ManagedConnection" // bundled with Tomcat
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("updateTransactionStatus"),
        Dbcp2ManagedConnectionInstrumentation.class.getName() + "$UpdateTransactionStatusAdvice");
  }

  public static class UpdateTransactionStatusAdvice {
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
