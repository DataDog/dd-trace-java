package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * This instrumentation adds the HandlerMappingResourceNameFilter definition to the spring context
 * When the context is created, the filter will be added to the beginning of the filter chain
 */
@AutoService(InstrumenterModule.class)
public class WebApplicationContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public WebApplicationContextInstrumentation() {
    super("spring-web", "spring-path-filter");
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
        packageName + ".DataDogHttpServletResponseWrapper",
        packageName + ".DataDogHttpServletResponseWrapper$CustomServletOutputStream",
        packageName+".ContentCachingResponseWrapper",
        packageName+".ContentCachingResponseWrapper$ResponseServletOutputStream",
        packageName+".ContentCachingResponseWrapper$ResponsePrintWriter",
        packageName+".ContentCachingRequestWrapper",
        packageName+".ContentCachingRequestWrapper$ContentCachingInputStream",
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
        WebApplicationContextInstrumentation.class.getName() + "$FilterInjectingAdvice");
  }

  public static class FilterInjectingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final ConfigurableListableBeanFactory beanFactory) {
      if (beanFactory instanceof BeanDefinitionRegistry
          && !beanFactory.containsBean("ddDispatcherFilter")) {

        ((BeanDefinitionRegistry) beanFactory)
            .registerBeanDefinition(
                "ddDispatcherFilter", new HandlerMappingResourceNameFilter.BeanDefinition());
      }
    }
  }
}
