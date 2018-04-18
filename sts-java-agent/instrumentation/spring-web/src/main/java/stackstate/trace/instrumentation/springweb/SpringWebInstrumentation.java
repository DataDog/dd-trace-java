package stackstate.trace.instrumentation.springweb;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClassWithField;

import com.google.auto.service.AutoService;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import org.springframework.web.servlet.HandlerMapping;
import stackstate.trace.agent.tooling.DDAdvice;
import stackstate.trace.agent.tooling.DDTransformers;
import stackstate.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class SpringWebInstrumentation extends Instrumenter.Configurable {

  public SpringWebInstrumentation() {
    super("spring-web");
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            not(isInterface())
                .and(
                    failSafe(
                        hasSuperType(named("org.springframework.web.servlet.HandlerAdapter")))),
            classLoaderHasClassWithField(
                "org.springframework.web.servlet.HandlerMapping",
                "BEST_MATCHING_PATTERN_ATTRIBUTE"))
        .transform(DDTransformers.defaultTransformers())
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(isPublic())
                        .and(nameStartsWith("handle"))
                        .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest"))),
                    SpringWebNamingAdvice.class.getName()))
        .type(not(isInterface()).and(named("org.springframework.web.servlet.DispatcherServlet")))
        .transform(
            DDAdvice.create()
                .advice(
                    isMethod()
                        .and(isProtected())
                        .and(nameStartsWith("processHandlerException"))
                        .and(takesArgument(3, Exception.class)),
                    SpringWebErrorHandlerAdvice.class.getName()))
        .asDecorator();
  }

  public static class SpringWebNamingAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void nameResource(@Advice.Argument(0) final HttpServletRequest request) {
      final Scope scope = GlobalTracer.get().scopeManager().active();
      if (scope != null && request != null) {
        final String method = request.getMethod();
        final Object bestMatchingPattern =
            request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (method != null && bestMatchingPattern != null) {
          final String resourceName = method + " " + bestMatchingPattern;
          scope.span().setTag(DDTags.RESOURCE_NAME, resourceName);
          scope.span().setTag(DDTags.SPAN_TYPE, DDSpanTypes.WEB_SERVLET);
        }
      }
    }
  }

  public static class SpringWebErrorHandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void nameResource(@Advice.Argument(3) final Exception exception) {
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
}
