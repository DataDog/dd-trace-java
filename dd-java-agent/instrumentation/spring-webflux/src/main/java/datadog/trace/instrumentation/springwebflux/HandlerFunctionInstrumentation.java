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
import org.springframework.web.reactive.function.server.ServerRequest;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import static datadog.trace.agent.tooling.ByteBuddyElementMatchers.safeHasSuperType;
import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(Instrumenter.class)
public final class HandlerFunctionInstrumentation extends Instrumenter.Default {

  public HandlerFunctionInstrumentation() {
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
        .and(safeHasSuperType(named("org.springframework.web.reactive.function.server.HandlerFunction")));
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    return Collections.<ElementMatcher, String>singletonMap(
        isMethod()
            // .and(isPublic())
            .and(named("handle")),
//           .and(takesArgument(0, named("org.springframework.web.server.ServerRequest")))
//           .and(takesArguments(1)),
      HandlerFunctionAdvice.class.getName());
  }

  public static class HandlerFunctionAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope nameResourceAndStartSpan(
        @Advice.Argument(0) final ServerRequest request, @Advice.Origin Method method) {
      LoggerFactory.getLogger(HandlerFunction.class).warn("THREAD handler: " + Thread.currentThread().toString());
      LoggerFactory.getLogger(HandlerFunction.class).warn("TIME handler: " + System.currentTimeMillis());
      LoggerFactory.getLogger(HandlerFunction.class).warn("HERE handler: " + GlobalTracer.get().activeSpan().toString());

      return GlobalTracer.get()
          .buildSpan("HandlerFunction.handle")
          .withTag("method.name", method.getName())
          .withTag("method.declaringClass", method.getDeclaringClass().getName())
          .withTag(Tags.COMPONENT.getKey(), "spring-webflux-controller")
          .startActive(true);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
      @Advice.Enter final Scope scope, @Advice.Thrown final Throwable throwable) {
      if (scope != null) {
        final Span span = scope.span();
        if (throwable != null) {
          Tags.ERROR.set(span, true);
          span.log(Collections.singletonMap(ERROR_OBJECT, throwable));
        }
        scope.close();
      }
    }
  }

}
