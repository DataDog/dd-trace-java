package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.concreteClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class HandlerAdapterInstrumentation extends AbstractWebfluxInstrumentation
    implements Instrumenter.ForTypeHierarchy {

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.web.reactive.HandlerAdapter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return concreteClass().and(implementsInterface(named(hierarchyMarkerType())));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("handle"))
            .and(takesArgument(0, named("org.springframework.web.server.ServerWebExchange")))
            .and(takesArgument(1, named("java.lang.Object")))
            .and(takesArguments(2)),
        // Cannot reference class directly here because it would lead to class load failure on Java7
        packageName + ".HandlerAdapterAdvice");
  }
}
