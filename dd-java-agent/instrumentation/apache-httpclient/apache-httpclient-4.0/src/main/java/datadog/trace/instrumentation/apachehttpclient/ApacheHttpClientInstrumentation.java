package datadog.trace.instrumentation.apachehttpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;

@AutoService(InstrumenterModule.class)
public class ApacheHttpClientInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {

  static final String[] MATCHING_TYPES =
      new String[] {
        "org.apache.http.impl.client.AbstractHttpClient",
        "software.amazon.awssdk.http.apache.internal.impl.ApacheSdkHttpClient",
        "org.apache.http.impl.client.AutoRetryHttpClient",
        "org.apache.http.impl.client.CloseableHttpClient",
        "org.apache.http.impl.client.ContentEncodingHttpClient",
        "org.apache.http.impl.client.DecompressingHttpClient",
        "org.apache.http.impl.client.DefaultHttpClient",
        "org.apache.http.impl.client.InternalHttpClient",
        "org.apache.http.impl.client.MinimalHttpClient",
        "org.apache.http.impl.client.SystemDefaultHttpClient",
        "com.netflix.http4.NFHttpClient",
        "com.amazonaws.http.apache.client.impl.SdkHttpClient"
      };

  public ApacheHttpClientInstrumentation() {
    super("httpclient", "apache-httpclient", "apache-http-client");
  }

  @Override
  public boolean onlyMatchKnownTypes() {
    return isShortcutMatchingEnabled(false);
  }

  @Override
  public String[] knownMatchingTypes() {
    return MATCHING_TYPES;
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.http.client.HttpClient";
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
  public void methodAdvice(MethodTransformer transformer) {
    // There are 8 execute(...) methods.  Depending on the version, they may or may not delegate to
    // eachother. Thus, all methods need to be instrumented.  Because of argument position and type,
    // some methods can share the same advice class.  The call depth tracking ensures only 1 span is
    // created

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest"))),
        ApacheHttpClientInstrumentation.class.getName() + "$UriRequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest")))
            .and(takesArgument(1, named("org.apache.http.protocol.HttpContext"))),
        ApacheHttpClientInstrumentation.class.getName() + "$UriRequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest")))
            .and(takesArgument(1, named("org.apache.http.client.ResponseHandler"))),
        ApacheHttpClientInstrumentation.class.getName() + "$UriRequestWithHandlerAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest")))
            .and(takesArgument(1, named("org.apache.http.client.ResponseHandler")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext"))),
        ApacheHttpClientInstrumentation.class.getName() + "$UriRequestWithHandlerAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest"))),
        ApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext"))),
        ApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.client.ResponseHandler"))),
        ApacheHttpClientInstrumentation.class.getName() + "$RequestWithHandlerAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.client.ResponseHandler")))
            .and(takesArgument(3, named("org.apache.http.protocol.HttpContext"))),
        ApacheHttpClientInstrumentation.class.getName() + "$RequestWithHandlerAdvice");
  }

  public static class UriRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(@Advice.Argument(0) final HttpUriRequest request) {
      try {
        return HelperMethods.doMethodEnter(request);
      } catch (BlockingException e) {
        HelperMethods.onBlockingRequest();
        // re-throw blocking exceptions
        throw e;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      HelperMethods.doMethodExit(scope, result, throwable);
    }
  }

  public static class UriRequestWithHandlerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) final HttpUriRequest request,
        @Advice.Argument(
                value = 1,
                optional = true,
                typing = Assigner.Typing.DYNAMIC,
                readOnly = false)
            Object handler) {

      try {
        final AgentScope scope = HelperMethods.doMethodEnter(request);
        // Wrap the handler so we capture the status code
        if (null != scope && handler instanceof ResponseHandler) {
          handler =
              new WrappingStatusSettingResponseHandler(scope.span(), (ResponseHandler) handler);
        }
        return scope;
      } catch (BlockingException e) {
        HelperMethods.onBlockingRequest();
        // re-throw blocking exceptions
        throw e;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      HelperMethods.doMethodExit(scope, result, throwable);
    }
  }

  public static class RequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) final HttpHost host, @Advice.Argument(1) final HttpRequest request) {
      try {
        if (request instanceof HttpUriRequest) {
          return HelperMethods.doMethodEnter((HttpUriRequest) request);
        } else {
          return HelperMethods.doMethodEnter(host, request);
        }
      } catch (BlockingException e) {
        HelperMethods.onBlockingRequest();
        // re-throw blocking exceptions
        throw e;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return final Object result,
        @Advice.Thrown final Throwable throwable) {
      HelperMethods.doMethodExit(scope, result, throwable);
    }
  }

  public static class RequestWithHandlerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.Argument(0) final HttpHost host,
        @Advice.Argument(1) final HttpRequest request,
        @Advice.Argument(
                value = 2,
                optional = true,
                typing = Assigner.Typing.DYNAMIC,
                readOnly = false)
            Object handler) {
      try {
        final AgentScope scope;
        if (request instanceof HttpUriRequest) {
          scope = HelperMethods.doMethodEnter((HttpUriRequest) request);
        } else {
          scope = HelperMethods.doMethodEnter(host, request);
        }
        // Wrap the handler so we capture the status code
        if (null != scope && handler instanceof ResponseHandler) {
          handler =
              new WrappingStatusSettingResponseHandler(scope.span(), (ResponseHandler) handler);
        }
        return scope;
      } catch (BlockingException e) {
        HelperMethods.onBlockingRequest();
        // re-throw blocking exceptions
        throw e;
      }
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
