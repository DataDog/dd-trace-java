package stackstate.trace.instrumentation.play;

import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClassWithMethod;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;
import static net.bytebuddy.matcher.ElementMatchers.*;

import akka.japi.JavaPartialFunction;
import com.google.auto.service.AutoService;
import stackstate.trace.agent.tooling.*;
import stackstate.trace.api.STSSpanTypes;
import stackstate.trace.api.STSTags;
import stackstate.trace.context.TraceScope;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.LoggerFactory;
import play.api.mvc.Action;
import play.api.mvc.Request;
import play.api.mvc.Result;
import scala.Option;
import scala.Tuple2;
import scala.concurrent.Future;

@Slf4j
@AutoService(Instrumenter.class)
public final class PlayInstrumentation extends Instrumenter.Default {

  public PlayInstrumentation() {
    super("play");
  }

  @Override
  public ElementMatcher typeMatcher() {
    return hasSuperType(named("play.api.mvc.Action"));
  }

  @Override
  public ElementMatcher<? super ClassLoader> classLoaderMatcher() {
    return classLoaderHasClasses(
            "akka.japi.JavaPartialFunction",
            "play.api.mvc.Action",
            "play.api.mvc.Result",
            "scala.Option",
            "scala.Tuple2",
            "scala.concurrent.Future")
        .and(classLoaderHasClassWithMethod("play.api.mvc.Request", "tags"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      PlayInstrumentation.class.getName() + "$RequestCallback",
      PlayInstrumentation.class.getName() + "$RequestError",
      PlayInstrumentation.class.getName() + "$PlayHeaders"
    };
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        named("apply")
            .and(takesArgument(0, named("play.api.mvc.Request")))
            .and(returns(named("scala.concurrent.Future"))),
        PlayAdvice.class.getName());
    return transformers;
  }

  public static class PlayAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope startSpan(@Advice.Argument(0) final Request req) {
      final Scope scope;
      if (GlobalTracer.get().activeSpan() == null) {
        final SpanContext extractedContext;
        if (GlobalTracer.get().scopeManager().active() == null) {
          extractedContext =
              GlobalTracer.get().extract(Format.Builtin.HTTP_HEADERS, new PlayHeaders(req));
        } else {
          extractedContext = null;
        }
        scope =
            GlobalTracer.get()
                .buildSpan("play.request")
                .asChildOf(extractedContext)
                .startActive(false);
      } else {
        // An upstream framework (e.g. akka-http, netty) has already started the span.
        // Do not extract the context.
        scope = GlobalTracer.get().buildSpan("play.request").startActive(false);
      }

      if (GlobalTracer.get().scopeManager().active() instanceof TraceScope) {
        ((TraceScope) GlobalTracer.get().scopeManager().active()).setAsyncPropagation(true);
      }
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopTraceOnResponse(
        @Advice.Enter final Scope scope,
        @Advice.This final Object thisAction,
        @Advice.Thrown final Throwable throwable,
        @Advice.Argument(0) final Request req,
        @Advice.Return(readOnly = false) Future<Result> responseFuture) {
      // more about routes here: https://github.com/playframework/playframework/blob/master/documentation/manual/releases/release26/migration26/Migration26.md
      final Option pathOption = req.tags().get("ROUTE_PATTERN");
      if (!pathOption.isEmpty()) {
        final String path = (String) pathOption.get();
        scope.span().setTag(Tags.HTTP_URL.getKey(), path);
        scope.span().setTag(STSTags.RESOURCE_NAME, req.method() + " " + path);
      }

      scope.span().setTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER);
      scope.span().setTag(Tags.HTTP_METHOD.getKey(), req.method());
      scope.span().setTag(STSTags.SPAN_TYPE, STSSpanTypes.WEB_SERVLET);
      scope.span().setTag(Tags.COMPONENT.getKey(), "play-action");

      if (throwable == null) {
        responseFuture.onFailure(
            new RequestError(scope.span()), ((Action) thisAction).executionContext());
        responseFuture =
            responseFuture.map(
                new RequestCallback(scope.span()), ((Action) thisAction).executionContext());
      } else {
        RequestError.onError(scope.span(), throwable);
        scope.span().finish();
      }
      scope.close();

      final Span rootSpan = GlobalTracer.get().activeSpan();
      if (rootSpan != null && !pathOption.isEmpty()) {
        // set the resource name on the upstream akka/netty span
        final String path = (String) pathOption.get();
        rootSpan.setTag(STSTags.RESOURCE_NAME, req.method() + " " + path);
      }
    }
  }

  public static class PlayHeaders implements TextMap {
    private final Request request;

    public PlayHeaders(Request request) {
      this.request = request;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      final scala.collection.Map scalaMap = request.headers().toSimpleMap();
      final Map<String, String> javaMap = new HashMap<>(scalaMap.size());
      final scala.collection.Iterator<Tuple2<String, String>> scalaIterator = scalaMap.iterator();
      while (scalaIterator.hasNext()) {
        final Tuple2<String, String> tuple = scalaIterator.next();
        javaMap.put(tuple._1(), tuple._2());
      }
      return javaMap.entrySet().iterator();
    }

    @Override
    public void put(String s, String s1) {
      throw new IllegalStateException("play headers can only be extracted");
    }
  }

  public static class RequestError extends JavaPartialFunction<Throwable, Object> {
    private final Span span;

    public RequestError(Span span) {
      this.span = span;
    }

    @Override
    public Object apply(Throwable t, boolean isCheck) throws Exception {
      try {
        if (GlobalTracer.get().scopeManager().active() instanceof TraceScope) {
          ((TraceScope) GlobalTracer.get().scopeManager().active()).setAsyncPropagation(false);
        }
        onError(span, t);
      } catch (Throwable t2) {
        LoggerFactory.getLogger(RequestCallback.class).debug("error in play instrumentation", t);
      }
      span.finish();
      return null;
    }

    public static void onError(final Span span, final Throwable t) {
      Tags.ERROR.set(span, Boolean.TRUE);
      span.log(Collections.singletonMap("error.object", t));
      Tags.HTTP_STATUS.set(span, 500);
    }
  }

  @Slf4j
  public static class RequestCallback extends scala.runtime.AbstractFunction1<Result, Result> {
    private final Span span;

    public RequestCallback(Span span) {
      this.span = span;
    }

    @Override
    public Result apply(Result result) {
      if (GlobalTracer.get().scopeManager().active() instanceof TraceScope) {
        ((TraceScope) GlobalTracer.get().scopeManager().active()).setAsyncPropagation(false);
      }
      try {
        Tags.HTTP_STATUS.set(span, result.header().status());
      } catch (Throwable t) {
        log.debug("error in play instrumentation", t);
      }
      span.finish();
      return result;
    }
  }
}
