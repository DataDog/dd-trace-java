package datadog.trace.instrumentation.datanucleus;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.DECORATE;
import static datadog.trace.instrumentation.datanucleus.DatanucleusDecorator.JAVA_DATANUCLEUS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

public class JDOTransactionInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.datanucleus.api.jdo.JDOTransaction";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(namedOneOf("commit", "rollback")),
        JDOTransactionInstrumentation.class.getName() + "$TransactionAdvice");
  }

  public static class TransactionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope start(
        @Advice.Origin("datanucleus.transaction.#m") final String operationName) {
      final AgentSpan span = startSpan(JAVA_DATANUCLEUS.toString(), operationName);

      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void end(
        @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }

      AgentSpan span = spanFromScope(scope);

      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(span);

      scope.close();
      span.finish();
    }
  }
}
