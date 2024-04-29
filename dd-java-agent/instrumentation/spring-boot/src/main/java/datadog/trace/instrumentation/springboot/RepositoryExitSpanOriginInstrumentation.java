package datadog.trace.instrumentation.springboot;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter.ForTypeHierarchy;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.InstrumenterModule.Tracing;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.Named;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class RepositoryExitSpanOriginInstrumentation extends Tracing implements ForTypeHierarchy {

  public RepositoryExitSpanOriginInstrumentation() {
    super("spring-boot-exit-span-origin-instrumentation");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
    //    return "org.springframework.data.repository.Repository";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    Named<TypeDescription> named = named("org.springframework.data.repository.Repository");
    //    Named<TypeDescription> named = named(hierarchyMarkerType());
    return HierarchyMatchers.implementsInterface(named)
        .or(HierarchyMatchers.hasSuperType(named))
        .or(HierarchyMatchers.extendsClass(named))
        .or(HierarchyMatchers.hasInterface(named));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    System.out.println("RepositoryExitSpanOriginInstrumentation.methodAdvice");

    transformer.applyAdvice(
        isMethod(), "datadog.trace.instrumentation.span_origin.ExitSpanOriginAdvice");
  }
}
