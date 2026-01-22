package datadog.trace.instrumentation.grizzly;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_PARENT_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.grizzly.GrizzlyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

public class GrizzlyHttpHandlerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.http.server.HttpHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("doHandle"))
            .and(takesArgument(0, named("org.glassfish.grizzly.http.server.Request")))
            .and(takesArgument(1, named("org.glassfish.grizzly.http.server.Response"))),
        GrizzlyHttpHandlerInstrumentation.class.getName() + "$ContextTrackingAdvice"),
        GrizzlyHttpHandlerInstrumentation.class.getName() + "$HandleAdvice");
  }

  @AppliesOn(InstrumenterModule.TargetSystem.CONTEXT_TRACKING)
  public static class ContextTrackingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static ContextScope methodEnter(
        @Advice.Argument(0) final Request request) {

      if (request.getAttribute(DD_PARENT_CONTEXT_ATTRIBUTE) != null) {
        // the tracing advice only activate the tracing span once. If we activate the parent each time we risk
        // to cause side effects. Hence also this is activated once the first time only.
        return null;
      }
      final Context context = DECORATE.extract(request);
      request.setAttribute(DD_PARENT_CONTEXT_ATTRIBUTE, context);
      return context.attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  public static class HandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static boolean /* skip body */ methodEnter(
        @Advice.Local("contextScope") ContextScope scope,
        @Advice.Argument(0) final Request request,
        @Advice.Argument(1) final Response response) {
      if (request.getAttribute(DD_CONTEXT_ATTRIBUTE) != null) {
        return false;
      }

      final Object parentContextObj = request.getAttribute(DD_PARENT_CONTEXT_ATTRIBUTE);
      final Context parentContext = (parentContextObj instanceof Context) ? (Context) parentContextObj : null;

      final Context context = DECORATE.startSpan(request, parentContext);
      final AgentSpan span = spanFromContext(context);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request, request, parentContext);

      scope = context.attach();

      request.setAttribute(DD_CONTEXT_ATTRIBUTE, context);
      request.setAttribute(
          CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
      request.setAttribute(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());

      Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
      if (rba != null) {
        boolean success = GrizzlyBlockingHelper.block(request, response, rba, context);
        if (success) {
          return true; /* skip body */
        }
      }

      request.addAfterServiceListener(SpanClosingListener.LISTENER);

      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter boolean skippedBody,
        @Advice.Return(readOnly = false) boolean retVal,
        @Advice.Local("contextScope") ContextScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      if (throwable != null) {
        final AgentSpan span = spanFromContext(scope.context());
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
      }
      scope.close();
      // span finished by SpanClosingListener

      if (skippedBody) {
        retVal = true; // return true to avoid suspending the request
      }
    }
  }
}
