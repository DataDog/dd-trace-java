package datadog.trace.instrumentation.spray;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.collection.JavaConverters;
import spray.http.HttpHeader;
import spray.http.HttpRequest;
import spray.routing.RequestContext;

@AutoService(Instrumenter.class)
public final class SprayHttpServerInstrumentation extends Instrumenter.Default {
  public SprayHttpServerInstrumentation() {
    super("spray-http", "spray-http-server");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("spray.routing.HttpServiceBase$class");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      SprayHttpServerInstrumentation.class.getPackage().getName() + ".SprayHelper$",
      SprayHttpServerInstrumentation.class.getName() + "$SprayHttpServerHeaders",
    };
  }

  /**
   * The approach here is slightly weird: Spray has 'nested' function called runSealedRoute that
   * runs route with all handlers wrapped around it. This gives us access to a 'final' response that
   * we can use to get all data for the span. Unfortunately this hides from us exception that might
   * have been through by the route. In order to capture that exception we have to also wrap 'inner' route.
   */
  @Override
  public Map<ElementMatcher, String> transformers() {
    final Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
      named("runSealedRoute$1").and(takesArgument(1, named("spray.routing.RequestContext"))),
      SprayHttpServerRunSealedRouteAdvice.class.getName());
    transformers.put(
      named("runRoute").and(takesArgument(1, named("scala.Function1"))),
      SprayHttpServerRunRouteAdvice.class.getName());
    return transformers;
  }

  public static class SprayHttpServerRunSealedRouteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope enter(@Advice.Argument(value = 1, readOnly = false) RequestContext ctx) {

      final SpanContext extractedContext =
        GlobalTracer.get()
          .extract(Format.Builtin.HTTP_HEADERS, new SprayHttpServerHeaders(ctx.request()));
      final Scope scope =
        GlobalTracer.get()
          .buildSpan("spray-http.request")
          .asChildOf(extractedContext)
          .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
          .withTag(Tags.HTTP_METHOD.getKey(), ctx.request().method().value())
          .withTag(DDTags.SPAN_TYPE, DDSpanTypes.HTTP_SERVER)
          .withTag(Tags.COMPONENT.getKey(), "spray-http-server")
          .withTag(Tags.HTTP_URL.getKey(), ctx.request().uri().toString())
          .startActive(false);

      if (scope instanceof TraceScope) {
        ((TraceScope) scope).setAsyncPropagation(true);
      }

      ctx = SprayHelper.wrapRequestContext(ctx, scope.span());
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Enter final Scope scope) {
      scope.close();
    }
  }

  public static class SprayHttpServerRunRouteAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(value = 1, readOnly = false) scala.Function1 route) {
      route = SprayHelper.wrapRoute(route);
    }
  }

  public static class SprayHttpServerHeaders implements TextMap {
    private final HttpRequest request;

    public SprayHttpServerHeaders(final HttpRequest request) {
      this.request = request;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      final Map<String, String> javaMap = new HashMap<>(request.headers().size());

      for (final HttpHeader header :
        JavaConverters.asJavaListConverter(request.headers()).asJava()) {
        javaMap.put(header.name(), header.value());
      }

      return javaMap.entrySet().iterator();
    }

    @Override
    public void put(final String name, final String value) {
      throw new IllegalStateException("spray http server headers can only be extracted");
    }
  }
}
