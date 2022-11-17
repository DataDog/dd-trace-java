package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.DECORATE;
import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.SCOPE_CONTEXT_KEY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.amazonaws.AmazonClientException;
import com.amazonaws.Request;
import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

/**
 * This is additional 'helper' to catch cases when HTTP request throws exception different from
 * {@link AmazonClientException} (for example an error thrown by another handler). In these cases
 * {@link RequestHandler2#afterError} is not called.
 */
@AutoService(Instrumenter.class)
public class AWSHttpClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public AWSHttpClientInstrumentation() {
    super("aws-sdk");
  }

  @Override
  public String instrumentedType() {
    return "com.amazonaws.http.AmazonHttpClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".OnErrorDecorator"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("doExecute")),
        AWSHttpClientInstrumentation.class.getName() + "$HttpClientAdvice");
  }

  public static class HttpClientAdvice {
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Argument(value = 0, optional = true) final Request<?> request,
        @Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
        if (scope != null) {
          request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
          final AgentSpan span = scope.span();
          DECORATE.onError(span, throwable);
          DECORATE.beforeFinish(span);
          scope.close();
          span.finish();
        }
      }
    }
  }

  /**
   * Due to a change in the AmazonHttpClient class, this instrumentation is needed to support newer
   * versions. The above class should cover older versions.
   */
  @AutoService(Instrumenter.class)
  public static final class RequestExecutorInstrumentation extends AWSHttpClientInstrumentation {

    @Override
    public String instrumentedType() {
      return "com.amazonaws.http.AmazonHttpClient$RequestExecutor";
    }

    @Override
    public String[] helperClassNames() {
      return new String[] {packageName + ".OnErrorDecorator"};
    }

    @Override
    public void adviceTransformations(AdviceTransformation transformation) {
      transformation.applyAdvice(
          isMethod().and(named("doExecute")),
          RequestExecutorInstrumentation.class.getName() + "$RequestExecutorAdvice");
    }

    public static class RequestExecutorAdvice {
      @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
      public static void methodExit(
          @Advice.FieldValue("request") final Request<?> request,
          @Advice.Thrown final Throwable throwable) {
        if (throwable != null) {
          final AgentScope scope = request.getHandlerContext(SCOPE_CONTEXT_KEY);
          if (scope != null) {
            request.addHandlerContext(SCOPE_CONTEXT_KEY, null);
            final AgentSpan span = scope.span();
            DECORATE.onError(span, throwable);
            DECORATE.beforeFinish(span);
            scope.close();
            span.finish();
          }
        }
      }
    }
  }
}
