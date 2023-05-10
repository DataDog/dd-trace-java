package datadog.trace.instrumentation.apachehttpclient5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.protocol.HttpContext;

@AutoService(Instrumenter.class)
public class ApacheHttpClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.CanShortcutTypeMatching {

  public ApacheHttpClientInstrumentation() {
    super("httpclient5", "apache-httpclient5", "apache-http-client5");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.apache.hc.client5.http.impl.classic.CloseableHttpClient",
      "org.apache.hc.client5.http.impl.classic.MinimalHttpClient"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.hc.client5.http.classic.HttpClient";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ApacheHttpClientDecorator",
      packageName + ".HttpHeadersInjectAdapter",
      packageName + ".HostAndRequestAsHttpUriRequest",
      packageName + ".HelperMethods",
      packageName + ".WrappingStatusSettingResponseHandler",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.hc.core5.http.ClassicHttpRequest"))),
        ApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.hc.core5.http.ClassicHttpRequest")))
            .and(takesArgument(1, named("org.apache.hc.core5.http.protocol.HttpContext"))),
        ApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.hc.core5.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.hc.core5.http.ClassicHttpRequest")))
            .and(takesArgument(2, named("org.apache.hc.core5.http.protocol.HttpContext"))),
        ApacheHttpClientInstrumentation.class.getName() + "$HostRequestAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.hc.core5.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.hc.core5.http.ClassicHttpRequest"))),
        ApacheHttpClientInstrumentation.class.getName() + "$HostRequestAdvice");

    transformation.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.apache.hc.core5.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.hc.core5.http.ClassicHttpRequest")))
            .and(takesArgument(2, named("org.apache.hc.core5.http.protocol.HttpContext")))
            .and(takesArgument(3, named("org.apache.hc.core5.http.io.HttpClientResponseHandler"))),
        ApacheHttpClientInstrumentation.class.getName() + "$ResponseHandlerAdvice");
  }

  public static class RequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(0) final ClassicHttpRequest request) {
      return HelperMethods.doMethodEnter(request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      HelperMethods.doMethodExit(scope, result, throwable);
    }
  }

  public static class HostRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) final HttpHost host,
        @Advice.Argument(1) final ClassicHttpRequest request) {
      return HelperMethods.doMethodEnter(host, request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      HelperMethods.doMethodExit(scope, result, throwable);
    }
  }

  public static class ResponseHandlerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) final HttpHost host,
        @Advice.Argument(1) final ClassicHttpRequest request,
        @Advice.Argument(2) final HttpContext context,
        @Advice.Argument(
                value = 3,
                optional = true,
                typing = Assigner.Typing.DYNAMIC,
                readOnly = false)
            Object handler) {
      final AgentScope scope = HelperMethods.doMethodEnter(host, request);
      // Wrap the handler so we capture the status code
      if (null != scope && handler instanceof HttpClientResponseHandler) {
        handler =
            new WrappingStatusSettingResponseHandler(
                scope.span(), (HttpClientResponseHandler) handler);
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      HelperMethods.doMethodExit(scope, result, throwable);
    }
  }
}
