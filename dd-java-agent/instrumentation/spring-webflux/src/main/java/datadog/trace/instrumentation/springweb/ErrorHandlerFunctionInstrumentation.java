package datadog.trace.instrumentation.springweb;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public final class ErrorHandlerFunctionInstrumentation extends Instrumenter.Default {

  public ErrorHandlerFunctionInstrumentation() {
    super("spring-webflux");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return
      not(isInterface())
        .and(safeHasSuperType(named("org.springframework.web.server.WebFilter")));
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
      public static void nameResource(@Advice.Return(readOnly = false) Mono returnMono) {
        returnMono.doOnSuccessOrError()
        final Scope scope = GlobalTracer.get().scopeManager().active();
        if (scope != null && exception != null) {
          final Span span = scope.span();
          span.log(Collections.singletonMap(ERROR_OBJECT, exception));
          // We want to capture the stacktrace, but that doesn't mean it should be an error.
          // We rely on a decorator to set the error state based on response code. (5xx -> error)
          Tags.ERROR.set(span, false);
        }
      }
    }

    public static class ResourceNamingConsumer<T> implements BiConsumer<T, Throwable> {

      @Override
      public void accept(T t, Throwable throwable) {
        
      }
    }
  }



}
