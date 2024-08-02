package datadog.trace.instrumentation.apachehttpclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.apachehttpclient.ApacheHttpClientInstrumentation.MATCHING_TYPES;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.instrumentation.appsec.rasp.modules.NetworkConnectionModule;
import datadog.trace.instrumentation.appsec.utils.InstrumentationLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpUriRequest;

@AutoService(InstrumenterModule.class)
public class RaspApacheHttpClientInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.CanShortcutTypeMatching {

  public RaspApacheHttpClientInstrumentation() {
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
      RaspHelperMethods.class.getName(),
      InstrumentationLogger.class.getName(),
      NetworkConnectionModule.class.getName()
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // There are 8 execute(...) methods.  Depending on the version, they may or may not delegate to
    // eachother. Thus, all methods need to be instrumented.  Because of argument position and type,
    // some methods can share the same advice class.  The call depth tracking ensures only 1 call to
    // Ssrf module

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$UriRequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest")))
            .and(takesArgument(1, named("org.apache.http.protocol.HttpContext"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$UriRequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest")))
            .and(takesArgument(1, named("org.apache.http.client.ResponseHandler"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$UriRequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.client.methods.HttpUriRequest")))
            .and(takesArgument(1, named("org.apache.http.client.ResponseHandler")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$UriRequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.client.ResponseHandler"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.apache.http.HttpHost")))
            .and(takesArgument(1, named("org.apache.http.HttpRequest")))
            .and(takesArgument(2, named("org.apache.http.client.ResponseHandler")))
            .and(takesArgument(3, named("org.apache.http.protocol.HttpContext"))),
        RaspApacheHttpClientInstrumentation.class.getName() + "$RequestAdvice");
  }

  public static class UriRequestAdvice {
    @Advice.OnMethodEnter()
    public static void methodEnter(@Advice.Argument(0) final HttpUriRequest request) {
      RaspHelperMethods.doMethodEnter(request);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit() {
      RaspHelperMethods.doMethodExit();
    }
  }

  public static class RequestAdvice {
    @Advice.OnMethodEnter()
    public static void methodEnter(
        @Advice.Argument(0) final HttpHost host, @Advice.Argument(1) final HttpRequest request) {
      if (request instanceof HttpUriRequest) {
        RaspHelperMethods.doMethodEnter((HttpUriRequest) request);
      } else {
        RaspHelperMethods.doMethodEnter(host, request);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit() {
      RaspHelperMethods.doMethodExit();
    }
  }
}
