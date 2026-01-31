package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Collections;
import java.util.Map;

@AutoService(InstrumenterModule.class)
public final class DispatcherHandlerInstrumentation extends AbstractWebfluxInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.springframework.web.reactive.DispatcherHandler";
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("org.reactivestreams.Publisher", Context.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("handle"))
            .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange")))
            .and(takesArguments(1)),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".DispatcherHandlerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("handleResult"))
            .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange"))),
        packageName + ".HandleResultAdvice");
  }
}
