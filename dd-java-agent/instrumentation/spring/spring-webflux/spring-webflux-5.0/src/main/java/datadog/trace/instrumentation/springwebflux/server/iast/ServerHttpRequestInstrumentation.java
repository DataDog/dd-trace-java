package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** @see org.springframework.http.server.reactive.ServerHttpRequest */
@AutoService(InstrumenterModule.class)
public class ServerHttpRequestInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public ServerHttpRequestInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.http.server.reactive.ServerHttpRequest";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameEndsWith("ServerHttpRequest").and(implementsInterface(named(hierarchyMarkerType())));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".TaintFluxElementsFunction"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("getQueryParams")).and(takesArguments(0)),
        packageName + ".TaintQueryParamsAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getCookies")).and(takesArguments(0)),
        packageName + ".TaintCookiesAdvice");
    transformer.applyAdvice(
        isMethod().and(named("getBody")).and(takesArguments(0)),
        packageName + ".TaintGetBodyAdvice");
  }
}
