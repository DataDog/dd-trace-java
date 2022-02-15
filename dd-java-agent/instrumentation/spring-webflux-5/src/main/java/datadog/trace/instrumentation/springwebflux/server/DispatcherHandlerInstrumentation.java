package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public final class DispatcherHandlerInstrumentation extends AbstractWebfluxInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "org.springframework.web.reactive.DispatcherHandler";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("handle"))
            .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange")))
            .and(takesArguments(1)),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".DispatcherHandlerAdvice");
  }
}
