package datadog.trace.instrumentation.springweb6;

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
 * Opt-in alternative to the default {@code getHandler}/{@code ControllerAdvice} route naming. Adds
 * the {@link HandlerMappingResourceNameFilter} bean to the Spring context, which names the route at
 * the start of the filter chain by resolving the handler itself. This is the legacy approach and is
 * disabled by default; enable it with {@code dd.integration.spring-path-filter.enabled=true}.
 */
@AutoService(InstrumenterModule.class)
public class WebApplicationContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public WebApplicationContextInstrumentation() {
    super("spring-web", "spring-path-filter");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
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
      packageName + ".SpringWebHttpServerDecorator",
      packageName + ".ServletRequestURIAdapter",
      packageName + ".HandlerMappingResourceNameFilter",
      packageName + ".HandlerMappingResourceNameFilter$BeanDefinition",
      packageName + ".PathMatchingHttpServletRequestWrapper",
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
        packageName + ".FilterInjectingAdvice");
  }
}
