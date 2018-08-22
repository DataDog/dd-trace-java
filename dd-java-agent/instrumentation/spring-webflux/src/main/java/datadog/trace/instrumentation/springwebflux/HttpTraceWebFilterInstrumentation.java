package datadog.trace.instrumentation.springwebflux;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public final class HttpTraceWebFilterInstrumentation extends Instrumenter.Default {

  public HttpTraceWebFilterInstrumentation() {
    super("spring-webflux");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
      HttpTraceWebFilterInstrumentation.class.getPackage().getName() + ".MonoDualConsumer"
    };
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    // return not(isInterface())
    //     .and(safeHasSuperType(named("org.springframework.web.server.WebFilter")));

    return named("org.springframework.boot.actuate.web.trace.reactive.HttpTraceWebFilter");    
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    return Collections.<ElementMatcher, String>singletonMap(
        isMethod()
            // .and(isPublic())
            .and(named("filter"))
            .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange")))
            .and(takesArgument(1, named("org.springframework.web.server.WebFilterChain")))
            .and(takesArguments(2)),
      ErrorHandlerAdvice.class.getName());
  }

    public static class ErrorHandlerAdvice {
      @Advice.OnMethodExit(suppress = Throwable.class)
      public static void nameResource(@Advice.Return(readOnly = false) Mono returnMono, @Advice.Origin final Method method) {
        LoggerFactory.getLogger(WebFilter.class).warn("THREAD filter: " + Thread.currentThread().toString());
        LoggerFactory.getLogger(WebFilter.class).warn("TIME filter: " + System.currentTimeMillis());
        LoggerFactory.getLogger(WebFilter.class).warn("HERE filter: " + GlobalTracer.get().activeSpan().toString());
        LoggerFactory.getLogger(WebFilter.class).warn("adding thing!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        LoggerFactory.getLogger(WebFilter.class).warn("METHOD: " + method.toString());
        // returnMono = returnMono.doOnSubscribe(new ResourceNamingBiConsumer());
        // returnMono = returnMono.doOnSuccessOrError(new ResourceNamingBiConsumer());
        MonoDualConsumer dualConsumer = new MonoDualConsumer("WebFilter.filter", true, true, true);
        returnMono = returnMono.doOnSubscribe(dualConsumer);
        returnMono = returnMono.doOnSuccessOrError(dualConsumer);
      }
    }

    public static class ResourceNamingBiConsumer<T> implements BiConsumer<T, Throwable> {

      @Override
      public void accept(T object, Throwable throwable) {
        LoggerFactory.getLogger(WebFilter.class).warn("has throwabe: " + (throwable != null));
        LoggerFactory.getLogger(WebFilter.class).warn("has object: " + (object != null));
        final Scope scope = GlobalTracer.get().scopeManager().active();
        if (scope != null && throwable != null) {
          final Span span = scope.span();
          span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
          // We want to capture the stacktrace, but that doesn't mean it should be an error.
          // We rely on a decorator to set the error state based on response code. (5xx -> error)
          Tags.ERROR.set(span, false);
        }
      }
    }

  }
