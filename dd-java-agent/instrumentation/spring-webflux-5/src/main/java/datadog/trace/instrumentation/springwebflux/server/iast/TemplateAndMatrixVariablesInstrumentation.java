package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

/** Obtain template and matrix variables for RequestMappingInfoHandlerMapping. */
@AutoService(Instrumenter.class)
public class TemplateAndMatrixVariablesInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public TemplateAndMatrixVariablesInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("handleMatch"))
            .and(
                takesArgument(
                    0, named("org.springframework.web.reactive.result.method.RequestMappingInfo")))
            .and(takesArgument(1, named("org.springframework.web.method.HandlerMethod")))
            .and(takesArgument(2, named("org.springframework.web.server.ServerWebExchange")))
            .and(takesArguments(3)),
        packageName + ".HandleMatchAdvice");
  }
}
