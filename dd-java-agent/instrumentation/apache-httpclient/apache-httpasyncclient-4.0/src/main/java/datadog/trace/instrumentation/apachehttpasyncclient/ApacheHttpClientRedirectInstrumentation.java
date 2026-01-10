package datadog.trace.instrumentation.apachehttpasyncclient;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.util.PropagationUtils;
import java.util.Locale;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.protocol.HttpContext;

/**
 * Early versions don't copy headers over on redirect. This instrumentation copies our headers over
 * manually. Inspired by
 * https://github.com/elastic/apm-agent-java/blob/master/apm-agent-plugins/apm-apache-httpclient-plugin/src/main/java/co/elastic/apm/agent/httpclient/ApacheHttpAsyncClientRedirectInstrumentation.java
 */
public class ApacheHttpClientRedirectInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.http.client.RedirectStrategy";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("getRedirect"))
            .and(takesArgument(2, named("org.apache.http.protocol.HttpContext"))),
        ApacheHttpClientRedirectInstrumentation.class.getName() + "$ClientRedirectAdvice");
  }

  public static class ClientRedirectAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    private static void onAfterExecute(
        @Advice.Argument(value = 2) final HttpContext context,
        @Advice.Return(typing = Assigner.Typing.DYNAMIC) final HttpRequest redirect) {
      if (redirect == null) {
        return;
      }
      Object originalRequest = context.getAttribute("http.request");
      if (!(originalRequest instanceof HttpRequest)) {
        return;
      }
      HttpRequest original = (HttpRequest) originalRequest;

      // Apache HttpClient 4.0.1+ copies headers from original to redirect only
      // if redirect headers are empty. Because we add headers
      // "x-datadog-" and "x-b3-" to redirect: it means redirect headers never
      // will be empty. So in case if not-instrumented redirect had no headers,
      // we just copy all not set headers from original to redirect (doing same
      // thing as apache httpclient does).
      if (!redirect.headerIterator().hasNext()) {
        // redirect didn't have other headers besides tracing, so we need to do copy
        // (same work as Apache HttpClient 4.0.1+ does w/o instrumentation)
        if (original instanceof HttpRequestWrapper) {
          // We should use the initial request because the wrapped one might contain more headers
          // (i.e. Host) we do not want to copy
          // if we cannot access the original request we cannot safely copy.
          // At this point we break the propagation not to corrupt the customer request
          redirect.setHeaders(((HttpRequestWrapper) original).getOriginal().getAllHeaders());
        }
      } else {
        for (final Header header : original.getAllHeaders()) {
          if (PropagationUtils.KNOWN_PROPAGATION_HEADERS.contains(
              header.getName().toLowerCase(Locale.ROOT))) {
            if (!redirect.containsHeader(header.getName())) {
              redirect.setHeader(header.getName(), header.getValue());
            }
          }
        }
      }
    }
  }
}
