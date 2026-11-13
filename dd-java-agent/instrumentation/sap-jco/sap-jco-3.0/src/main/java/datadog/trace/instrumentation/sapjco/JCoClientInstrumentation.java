package datadog.trace.instrumentation.sapjco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.sapjco.JCoClientDecorator.DECORATE;
import static datadog.trace.instrumentation.sapjco.JCoClientDecorator.OPERATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoFunction;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** Instruments {@code JCoFunction.execute(JCoDestination, ...)} — an outbound SAP RFC call. */
public class JCoClientInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "com.sap.conn.jco.JCoFunction";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArgument(0, named("com.sap.conn.jco.JCoDestination"))),
        getClass().getName() + "$ExecuteAdvice");
  }

  public static class ExecuteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.This JCoFunction function, @Advice.Argument(0) JCoDestination destination) {
      PassportRegistry.ensureClientRegistered();
      AgentSpan span = startSpan("sap-jco", OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, destination);
      DECORATE.onFunction(span, function);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter ContextScope scope, @Advice.Thrown Throwable throwable) {
      if (scope == null) {
        return;
      }
      AgentSpan span = spanFromScope(scope);
      DECORATE.onRfcError(span, throwable);
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
