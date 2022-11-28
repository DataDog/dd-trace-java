package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * This instrumentation adds the ServletPathRequestFilter definition to the spring context When the
 * context is created, the filter will be added to the beginning of the filter chain
 */
@AutoService(Instrumenter.class)
public class ServletPathRequestFilterInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public ServletPathRequestFilterInstrumentation() {
    super("spring-web", "spring-path-filter");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("org.springframework.web.filter.ServletRequestPathFilter")
        .and(hasClassNamed("javax.servlet.Filter"));
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("postProcessBeanFactory"))
            .and(
                takesArgument(
                    0,
                    named(
                        "org.springframework.beans.factory.config.ConfigurableListableBeanFactory"))),
        ServletPathRequestFilterInstrumentation.class.getName() + "$FilterInjectingAdvice");
  }

  public static class FilterInjectingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final ConfigurableListableBeanFactory beanFactory) {
      if (beanFactory instanceof BeanDefinitionRegistry
          && !beanFactory.containsBean("servletPathRequestFilter")) {

        ((BeanDefinitionRegistry) beanFactory)
            .registerBeanDefinition(
                "servletPathRequestFilter", new OrderedServletPathRequestFilter.BeanDefinition());
      }
    }
  }
}
