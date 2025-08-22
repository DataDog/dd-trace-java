package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closeActive;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.CONTEXT_CONTEXT_KEY;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.Request;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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

      final AgentSpan activeSpan = activeSpan();
      // check name in case TracingRequestHandler failed to activate the span
      if (activeSpan != null
          && (AwsNameCache.spanName(request).equals(activeSpan.getSpanName())
              || !activeSpan.isValid())) {
        closeActive();
      }

      if (throwable != null) {
        final Context context = request.getHandlerContext(CONTEXT_CONTEXT_KEY);
        if (context != null) {
          request.addHandlerContext(CONTEXT_CONTEXT_KEY, null);
          final AgentSpan span = spanFromContext(context);
          if (span != null) {
            DECORATE.onError(span, throwable);
            DECORATE.beforeFinish(span);
            span.finish();
          }
        }
      }
    }
  }
}
