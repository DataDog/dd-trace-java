package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.DECORATE;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.SPAN_CONTEXT_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.Request;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

/**
 * Due to a change in the AmazonHttpClient class, this instrumentation is needed to support newer
 * versions. The {@link AWSHttpClientInstrumentation} class should cover older versions.
 */
public final class RequestExecutorInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public RequestExecutorInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String instrumentedType() {
    return namespace + ".http.AmazonHttpClient$RequestExecutor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("doExecute")),
        RequestExecutorInstrumentation.class.getName() + "$RequestExecutorAdvice");
  }

  public static class RequestExecutorAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.FieldValue("request") final Request<?> request,
        @Advice.Thrown final Throwable throwable) {

      final AgentScope scope = activeScope();
      // check name in case TracingRequestHandler failed to activate the span
      if (scope != null
          && (AwsNameCache.spanName(request).equals(scope.span().getSpanName())
              || scope.span() instanceof AgentTracer.NoopAgentSpan)) {
        scope.close();
      }

      if (throwable != null) {
        final AgentSpan span = request.getHandlerContext(SPAN_CONTEXT_KEY);
        if (span != null) {
          request.addHandlerContext(SPAN_CONTEXT_KEY, null);
          DECORATE.onError(span, throwable);
          DECORATE.beforeFinish(span);
          span.finish();
        }
      }
    }
  }
}
