package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.closeActive;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.DECORATE;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.SPAN_CONTEXT_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.AmazonClientException;
import com.amazonaws.Request;
import com.amazonaws.handlers.RequestHandler2;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

/**
 * This is additional 'helper' to catch cases when HTTP request throws exception different from
 * {@link AmazonClientException} (for example an error thrown by another handler). In these cases
 * {@link RequestHandler2#afterError} is not called.
 */
public class AWSHttpClientInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  private final String namespace;

  public AWSHttpClientInstrumentation(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String instrumentedType() {
    return namespace + ".http.AmazonHttpClient";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("doExecute")),
        AWSHttpClientInstrumentation.class.getName() + "$HttpClientAdvice");
  }

  public static class HttpClientAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Argument(value = 0, optional = true) final Request<?> request,
        @Advice.Thrown final Throwable throwable) {

      final AgentSpan activeSpan = activeSpan();
      // check name in case TracingRequestHandler failed to activate the span
      if (activeSpan != null
          && (AwsNameCache.spanName(request).equals(activeSpan.getSpanName())
              || !activeSpan.isValid())) {
        closeActive();
      }

      if (throwable != null && request != null) {
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
