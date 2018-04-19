package stackstate.trace.instrumentation.servlet2;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;

import com.google.auto.service.AutoService;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.contrib.web.servlet.filter.HttpServletRequestExtractAdapter;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.trace.agent.tooling.DDAdvice;
import stackstate.trace.agent.tooling.DDTransformers;
import stackstate.trace.agent.tooling.HelperInjector;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.api.DDSpanTypes;
import stackstate.trace.api.DDTags;

@AutoService(Instrumenter.class)
public final class HttpServlet2Instrumentation extends Instrumenter.Configurable {
  public static final String SERVLET_OPERATION_NAME = "servlet.request";
  static final HelperInjector SERVLET2_HELPER_INJECTOR =
      new HelperInjector(
          "io.opentracing.contrib.web.servlet.filter.HttpServletRequestExtractAdapter",
          "io.opentracing.contrib.web.servlet.filter.HttpServletRequestExtractAdapter$MultivaluedMapFlatIterator",
          "datadog.trace.instrumentation.servlet2.ServletFilterSpanDecorator",
          "datadog.trace.instrumentation.servlet2.ServletFilterSpanDecorator$1");

  public HttpServlet2Instrumentation() {
    super("servlet", "servlet-2");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            not(isInterface()).and(failSafe(hasSuperType(named("javax.servlet.http.HttpServlet")))),
            not(classLoaderHasClasses("javax.servlet.AsyncEvent", "javax.servlet.AsyncListener"))
                .and(
                    classLoaderHasClasses(
                        "javax.servlet.ServletContextEvent", "javax.servlet.FilterChain")))
        .transform(SERVLET2_HELPER_INJECTOR)
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create(false) // Can't use the error handler for pre 1.5 classes...
                .advice(
                    named("service")
                        .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest")))
                        .and(takesArgument(1, named("javax.servlet.http.HttpServletResponse")))
                        .and(isProtected()),
                    HttpServlet2Advice.class.getName()))
        .asDecorator();
  }

  public static class HttpServlet2Advice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(@Advice.Argument(0) final ServletRequest req) {
      if (GlobalTracer.get().scopeManager().active() != null
          || !(req instanceof HttpServletRequest)) {
        // doFilter is called by each filter. We only want to time outer-most.
        return null;
      }

      final SpanContext extractedContext =
          GlobalTracer.get()
              .extract(
                  Format.Builtin.HTTP_HEADERS,
                  new HttpServletRequestExtractAdapter((HttpServletRequest) req));

      final Scope scope =
          GlobalTracer.get()
              .buildSpan(SERVLET_OPERATION_NAME)
              .asChildOf(extractedContext)
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
              .withTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET)
              .startActive(true);

      ServletFilterSpanDecorator.STANDARD_TAGS.onRequest((HttpServletRequest) req, scope.span());
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
          final HttpServletRequest req = (HttpServletRequest) request;
          final HttpServletResponse resp = (HttpServletResponse) response;

          if (throwable != null) {
            ServletFilterSpanDecorator.STANDARD_TAGS.onError(req, resp, throwable, span);
            span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
          } else {
            ServletFilterSpanDecorator.STANDARD_TAGS.onResponse(req, resp, span);
          }
        }
        scope.close();
      }
    }
  }
}
