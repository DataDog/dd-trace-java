package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.Header;
import org.apache.http.HttpRequest;

/**
 * Early versions don't copy headers over on redirect. This instrumentation copies our headers over
 * manually. Inspired by
 * https://github.com/elastic/apm-agent-java/blob/master/apm-agent-plugins/apm-apache-httpclient-plugin/src/main/java/co/elastic/apm/agent/httpclient/ApacheHttpAsyncClientRedirectInstrumentation.java
 */
@AutoService(Instrumenter.class)
public class ApacheHttpClientRedirectInstrumentation extends Instrumenter.Tracing {

  public ApacheHttpClientRedirectInstrumentation() {
    super("httpasyncclient", "apache-httpasyncclient");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.apache.http.client.RedirectStrategy");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("org.apache.http.client.RedirectStrategy"));
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("getRedirect"))
            .and(takesArgument(0, named("org.apache.http.HttpRequest"))),
        ApacheHttpClientRedirectInstrumentation.class.getName() + "$ClientRedirectAdvice");
  }

  public static class ClientRedirectAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    private static void onAfterExecute(
        @Advice.Argument(value = 0) final HttpRequest original,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final HttpRequest redirect) {
      if (redirect == null) {
        return;
      }
      // Apache HttpClient 4.0.1+ copies headers from original to redirect only
      // if redirect headers are empty. Because we add headers
      // "x-datadog-" and "x-b3-" to redirect: it means redirect headers never
      // will be empty. So in case if not-instrumented redirect had no headers,
      // we just copy all not set headers from original to redirect (doing same
      // thing as apache httpclient does).
      if (!redirect.headerIterator().hasNext()) {
        // redirect didn't have other headers besides tracing, so we need to do copy
        // (same work as Apache HttpClient 4.0.1+ does w/o instrumentation)
        redirect.setHeaders(original.getAllHeaders());
      } else {
        for (final Header header : original.getAllHeaders()) {
          final String name = header.getName().toLowerCase();
          if (name.startsWith("x-datadog-") || name.startsWith("x-b3-")) {
            if (!redirect.containsHeader(header.getName())) {
              redirect.setHeader(header.getName(), header.getValue());
            }
          }
        }
      }
    }
  }
}
