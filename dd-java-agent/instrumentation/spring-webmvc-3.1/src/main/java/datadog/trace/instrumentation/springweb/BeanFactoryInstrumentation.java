package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Bean Factories can be set with a "beanClassloader" that is different from the injected
 * classloader. This leads to a ClassNotFoundException at runtime.
 *
 * <p>This instrumentation ensures class lookups for the HandlerMappingResourceNameFilter don't fail
 * by manually setting the class on the bean definition
 *
 * <p>This can't be done at BeanDefinition construction time because Spring heavy use of clone()
 * which sometimes only copies the classname
 */
@AutoService(Instrumenter.class)
public class BeanFactoryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public BeanFactoryInstrumentation() {
    super("spring-web");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.beans.factory.support.AbstractBeanFactory";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("resolveBeanClass"))
            .and(
                takesArgument(
                    0, named("org.springframework.beans.factory.support.RootBeanDefinition"))),
        BeanFactoryInstrumentation.class.getName() + "$BeanResolvingAdvice");
  }

  public static class BeanResolvingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final RootBeanDefinition beanDefinition) {
      if (!beanDefinition.hasBeanClass()
          && HandlerMappingResourceNameFilter.class
              .getName()
              .equals(beanDefinition.getBeanClassName())) {

        beanDefinition.setBeanClass(HandlerMappingResourceNameFilter.class);
      }
    }
  }
}
