package stackstate.trace.instrumentation.servlet2;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.api.STSSpanTypes;
import stackstate.trace.api.STSTags;
import stackstate.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.contrib.web.servlet.filter.HttpServletRequestExtractAdapter;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class FilterChain2Instrumentation extends Instrumenter.Default {
  public static final String FILTER_CHAIN_OPERATION_NAME = "servlet.request";

  public FilterChain2Instrumentation() {
    super("servlet", "servlet-2");
  }

  @Override
  public ElementMatcher typeMatcher() {
    return not(isInterface()).and(failSafe(hasSuperType(named("javax.servlet.FilterChain"))));
  }

  @Override
  public ElementMatcher<? super ClassLoader> classLoaderMatcher() {
    return not(classLoaderHasClasses("javax.servlet.AsyncEvent", "javax.servlet.AsyncListener"))
        .and(
            classLoaderHasClasses(
                "javax.servlet.ServletContextEvent", "javax.servlet.ServletRequest"));
  }

  @Override
  public String[] helperClassNames() {
    return HttpServlet2Instrumentation.HELPERS;
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        named("doFilter")
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(isPublic()),
        FilterChain2Advice.class.getName());
    return transformers;
  }

  public static class FilterChain2Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(@Advice.Argument(0) final ServletRequest req) {
      if (GlobalTracer.get().scopeManager().active() != null
          || !(req instanceof HttpServletRequest)) {
        // doFilter is called by each filter. We only want to time outer-most.
        return null;
      }

      final HttpServletRequest httpServletRequest = (HttpServletRequest) req;
      final SpanContext extractedContext =
          GlobalTracer.get()
              .extract(
                  Format.Builtin.HTTP_HEADERS,
                  new HttpServletRequestExtractAdapter(httpServletRequest));

      final Scope scope =
          GlobalTracer.get()
              .buildSpan(FILTER_CHAIN_OPERATION_NAME)
              .asChildOf(extractedContext)
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
              .withTag(STSTags.SPAN_TYPE, STSSpanTypes.WEB_SERVLET)
              .withTag("servlet.context", httpServletRequest.getContextPath())
              .startActive(true);

      if (scope instanceof TraceScope) {
        ((TraceScope) scope).setAsyncPropagation(true);
      }

      final Span span = scope.span();
      Tags.COMPONENT.set(span, "java-web-servlet");
      Tags.HTTP_METHOD.set(span, httpServletRequest.getMethod());
      Tags.HTTP_URL.set(span, httpServletRequest.getRequestURL().toString());
      if (httpServletRequest.getUserPrincipal() != null) {
        span.setTag("user.principal", httpServletRequest.getUserPrincipal().getName());
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Argument(0) final ServletRequest request,
        @Advice.Argument(1) final ServletResponse response,
        @Advice.Enter final Scope scope,
        @Advice.Thrown final Throwable throwable) {

      if (scope != null) {
        if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
          final Span span = scope.span();

          if (throwable != null) {
            Tags.ERROR.set(span, Boolean.TRUE);
            span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
          }
        }
        scope.close();
      }
    }
  }
}
