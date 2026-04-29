package datadog.trace.instrumentation.springmessaging;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import static datadog.trace.instrumentation.springmessaging.SpringMessageDecorator.COMPONENT_NAME;
import static datadog.trace.instrumentation.springmessaging.SpringMessageDecorator.DECORATE;
import static datadog.trace.instrumentation.springmessaging.SpringMessageDecorator.SPRING_INBOUND;
import static datadog.trace.instrumentation.springmessaging.SpringMessageExtractAdapter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AsyncResultExtensions;
import net.bytebuddy.asm.Advice;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;

@AutoService(InstrumenterModule.class)
public final class SpringMessageHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SpringMessageHandlerInstrumentation() {
    super("spring-messaging", "spring-messaging-4");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.messaging.handler.invocation.InvocableHandlerMethod";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvices(
        isMethod()
            .and(
                named("invoke")
                    .and(takesArgument(0, named("org.springframework.messaging.Message")))),
        SpringMessageHandlerInstrumentation.class.getName() + "$ContextPropagationAdvice",
        SpringMessageHandlerInstrumentation.class.getName() + "$HandleMessageAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringMessageDecorator",
      packageName + ".SpringMessageExtractAdapter",
      packageName + ".SpringMessageExtractAdapter$1",
    };
  }

  @AppliesOn(CONTEXT_TRACKING)
  public static class ContextPropagationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) Message<?> message, @Advice.Local("ctxScope") ContextScope scope) {
      if (activeSpan() == null) {
        // no local active span, so extract from message to avoid disconnected trace
        scope = defaultPropagator().extract(getRootContext(), message, GETTER).attach();
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Local("ctxScope") ContextScope scope) {
      if (scope != null) scope.close();
    }
  }

  public static class HandleMessageAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This InvocableHandlerMethod thiz) {
      AgentSpan span = startSpan(COMPONENT_NAME.toString(), SPRING_INBOUND);
      DECORATE.afterStart(span);
      span.setResourceName(DECORATE.spanNameForMethod(thiz.getMethod()));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter AgentScope scope,
        @Advice.Return(readOnly = false) Object result,
        @Advice.Thrown Throwable error) {
      if (null == scope) {
        return;
      }
      AgentSpan span = scope.span();
      scope.close();
      if (null != error) {
        DECORATE.onError(span, error);
      }
      if (result != null) {
        Object wrappedResult =
            AsyncResultExtensions.wrapAsyncResult(result, result.getClass(), span);
        if (wrappedResult != null) {
          result = wrappedResult;
          // span will be finished by the wrapper
          return;
        }
      }
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
