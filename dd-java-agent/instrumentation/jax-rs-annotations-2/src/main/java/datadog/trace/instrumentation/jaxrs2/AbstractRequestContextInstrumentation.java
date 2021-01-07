package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public abstract class AbstractRequestContextInstrumentation extends Instrumenter.Tracing {
  public AbstractRequestContextInstrumentation() {
    super("jax-rs", "jaxrs", "jax-rs-filter");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("javax.ws.rs.container.ContainerRequestContext");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("javax.ws.rs.container.ContainerRequestContext"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.agent.tooling.ClassHierarchyIterable",
      "datadog.trace.agent.tooling.ClassHierarchyIterable$ClassIterator",
      packageName + ".JaxRsAnnotationsDecorator",
      packageName + ".JaxRsAnnotationsDecorator$1",
      packageName + ".RequestFilterHelper",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("abortWith"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("javax.ws.rs.core.Response"))),
        getClass().getName() + "$ContainerRequestContextAdvice");
  }
}
