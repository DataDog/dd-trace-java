package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public final class FilterInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  @Override
  public String hierarchyMarkerType() {
    return "org.glassfish.grizzly.filterchain.BaseFilter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()))
        // HttpCodecFilter is instrumented in the server instrumentation
        .and(not(named("org.glassfish.grizzly.http.HttpCodecFilter")))
        .and(not(named("org.glassfish.grizzly.http.HttpServerFilter")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("handleRead")
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(isPublic()),
        "datadog.trace.instrumentation.grizzlyhttp232.FilterAdvice");
  }
}
