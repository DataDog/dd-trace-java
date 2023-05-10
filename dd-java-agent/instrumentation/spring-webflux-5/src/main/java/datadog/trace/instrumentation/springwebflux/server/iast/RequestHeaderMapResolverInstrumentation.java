package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import org.springframework.core.MethodParameter;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.annotation.RequestHeaderMapMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;

/**
 * @see RequestHeaderMapMethodArgumentResolver#resolveArgumentValue(MethodParameter, BindingContext,
 *     ServerWebExchange)
 */
@AutoService(Instrumenter.class)
public class RequestHeaderMapResolverInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public RequestHeaderMapResolverInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.reactive.result.method.annotation.RequestHeaderMapMethodArgumentResolver";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("resolveArgumentValue"))
            .and(takesArgument(0, named("org.springframework.core.MethodParameter")))
            .and(takesArgument(1, named("org.springframework.web.reactive.BindingContext")))
            .and(takesArgument(2, named("org.springframework.web.server.ServerWebExchange")))
            .and(takesArguments(3)),
        packageName + ".RequestHeaderMapResolveAdvice");
  }
}
