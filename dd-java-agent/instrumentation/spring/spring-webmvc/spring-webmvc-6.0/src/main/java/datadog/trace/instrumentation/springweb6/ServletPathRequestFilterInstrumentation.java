package datadog.trace.instrumentation.springweb6;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Companion to {@link WebApplicationContextInstrumentation}: when the opt-in {@code
 * spring-path-filter} integration is enabled, registers Spring's {@code ServletRequestPathFilter}
 * first in the chain so the request path is parsed before {@link HandlerMappingResourceNameFilter}
 * resolves the handler. Disabled by default.
 */
@AutoService(InstrumenterModule.class)
public class ServletPathRequestFilterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public ServletPathRequestFilterInstrumentation() {
    super("spring-web", "spring-path-filter");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.springframework.web.filter.ServletRequestPathFilter")
        .and(hasClassNamed("jakarta.servlet.Filter"));
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.web.context.WebApplicationContext";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("org.springframework.context.support.AbstractApplicationContext"))
        .and(implementsInterface(named(hierarchyMarkerType())));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".OrderedServletPathRequestFilter",
      packageName + ".OrderedServletPathRequestFilter$BeanDefinition",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("postProcessBeanFactory"))
            .and(
                takesArgument(
                    0,
                    named(
                        "org.springframework.beans.factory.config.ConfigurableListableBeanFactory"))),
        packageName + ".ServletPathFilterInjectingAdvice");
  }
}
