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
    static void onAfterExecute(
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

      // Apache HttpClient 4.0.1+ copies headers from the original request to the redirect only when
      // the redirect request has no headers. Because tracing injects propagation headers before
      // redirect handling completes, an otherwise empty redirect request may no longer look empty
      // to HttpClient. Preserve that header-copy behavior only for same-origin redirects;
      // cross-origin redirects must not receive application headers such as Authorization or
      // Cookie.
      boolean emptyRedirect = !redirect.headerIterator().hasNext();
      if (emptyRedirect && RedirectHelper.isSameOrigin(context, original, redirect)) {
        redirect.setHeaders(((HttpRequestWrapper) original).getOriginal().getAllHeaders());
      } else {
        boolean copiedPropagationHeader = false;
        for (final Header header : original.getAllHeaders()) {
          if (PropagationUtils.KNOWN_PROPAGATION_HEADERS.contains(
              header.getName().toLowerCase(Locale.ROOT))) {
            if (!redirect.containsHeader(header.getName())) {
              redirect.setHeader(header.getName(), header.getValue());
              copiedPropagationHeader = true;
            }
          }
        }
        if (emptyRedirect && !copiedPropagationHeader) {
          // When there are no propagation headers to copy, add a harmless header to keep HttpClient
          // from treating the redirect as empty and copying application headers later.
          redirect.setHeader("x-datadog-redirect", "true");
        }
      }
    }
  }
}
