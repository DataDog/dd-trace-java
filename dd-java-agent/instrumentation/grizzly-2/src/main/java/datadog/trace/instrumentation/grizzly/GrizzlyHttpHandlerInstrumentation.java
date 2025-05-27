package datadog.trace.instrumentation.grizzly;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.grizzly.GrizzlyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.http.server.Request;
import org.glassfish.grizzly.http.server.Response;

@AutoService(InstrumenterModule.class)
public class GrizzlyHttpHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GrizzlyHttpHandlerInstrumentation() {
    super("grizzly");
  }

  @Override
  public boolean defaultEnabled() {
    return false;
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.http.server.HttpHandler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".GrizzlyDecorator",
      packageName + ".GrizzlyDecorator$GrizzlyBlockResponseFunction",
      packageName + ".RequestURIDataAdapter",
      packageName + ".SpanClosingListener",
      packageName + ".GrizzlyBlockingHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("doHandle"))
            .and(takesArgument(0, named("org.glassfish.grizzly.http.server.Request")))
            .and(takesArgument(1, named("org.glassfish.grizzly.http.server.Response"))),
        GrizzlyHttpHandlerInstrumentation.class.getName() + "$HandleAdvice");
  }

  public static class HandleAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static boolean /* skip body */ methodEnter(
        @Advice.Local("contextScope") ContextScope scope,
        @Advice.Argument(0) final Request request,
        @Advice.Argument(1) final Response response) {
      if (request.getAttribute(DD_SPAN_ATTRIBUTE) != null) {
        return false;
      }

      final Context parentContext = DECORATE.extractContext(request);
      final AgentSpan span = DECORATE.startSpan(request, parentContext);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request, request, parentContext);

      scope = parentContext.with(span).attach();

      request.setAttribute(DD_SPAN_ATTRIBUTE, span);
      request.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
      request.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());

      Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
      if (rba != null) {
        boolean success = GrizzlyBlockingHelper.block(request, response, rba, span);
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
