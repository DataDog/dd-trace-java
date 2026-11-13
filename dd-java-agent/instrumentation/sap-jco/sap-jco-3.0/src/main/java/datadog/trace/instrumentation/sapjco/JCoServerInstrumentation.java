package datadog.trace.instrumentation.sapjco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.sapjco.JCoServerDecorator.DECORATE;
import static datadog.trace.instrumentation.sapjco.JCoServerDecorator.OPERATION_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.server.JCoServerContext;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments {@code JCoServerFunctionHandler.handleRequest(JCoServerContext, JCoFunction)} — an
 * inbound SAP RFC request served by the application.
 */
public class JCoServerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "com.sap.conn.jco.server.JCoServerFunctionHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("handleRequest"))
            .and(takesArgument(0, named("com.sap.conn.jco.server.JCoServerContext")))
            .and(takesArgument(1, named("com.sap.conn.jco.JCoFunction"))),
        getClass().getName() + "$HandleRequestAdvice");
  }

  public static class HandleRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(0) JCoServerContext serverCtx, @Advice.Argument(1) JCoFunction function) {
      // Register the server passport manager so inbound passports are captured (first call may miss
      // it, since the manager can only be registered once a handler is actually invoked).
      PassportRegistry.ensureServerRegistered();
      InboundPassport inbound = InboundPassport.getAndClear();
      AgentSpan span = startSpan("sap-jco", OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, serverCtx, function, inbound);
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
