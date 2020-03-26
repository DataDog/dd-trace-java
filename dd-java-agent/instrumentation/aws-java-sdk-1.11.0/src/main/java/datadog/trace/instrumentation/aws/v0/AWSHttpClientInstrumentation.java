package datadog.trace.instrumentation.aws.v0;

import static datadog.trace.instrumentation.aws.v0.OnErrorDecorator.DECORATE;
import static datadog.trace.instrumentation.aws.v0.RequestMeta.SCOPE_CONTEXT_KEY;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.amazonaws.AmazonClientException;
import com.amazonaws.Request;
import com.amazonaws.handlers.RequestHandler2;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * This is additional 'helper' to catch cases when HTTP request throws exception different from
 * {@link AmazonClientException} (for example an error thrown by another handler). In these cases
 * {@link RequestHandler2#afterError} is not called.
 */
@AutoService(Instrumenter.class)
public class AWSHttpClientInstrumentation extends Instrumenter.Default {

  public AWSHttpClientInstrumentation() {
    super("aws-sdk");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.amazonaws.http.AmazonHttpClient");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OnErrorDecorator", packageName + ".RequestMeta",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(not(isAbstract())).and(named("doExecute")),
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
          DECORATE.onError(scope.span(), throwable);
          DECORATE.beforeFinish(scope.span());
          scope.close();
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
    public ElementMatcher<TypeDescription> typeMatcher() {
      return named("com.amazonaws.http.AmazonHttpClient$RequestExecutor");
    }

    @Override
    public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
      return singletonMap(
          isMethod().and(not(isAbstract())).and(named("doExecute")),
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
            DECORATE.onError(scope.span(), throwable);
            DECORATE.beforeFinish(scope.span());
            scope.close();
          }
        }
      }
    }
  }
}
