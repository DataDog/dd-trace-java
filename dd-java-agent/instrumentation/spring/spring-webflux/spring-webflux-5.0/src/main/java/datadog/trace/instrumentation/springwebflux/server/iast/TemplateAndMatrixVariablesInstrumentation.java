package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/** Obtain template and matrix variables for RequestMappingInfoHandlerMapping. */
@AutoService(InstrumenterModule.class)
public class TemplateAndMatrixVariablesInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public TemplateAndMatrixVariablesInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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
