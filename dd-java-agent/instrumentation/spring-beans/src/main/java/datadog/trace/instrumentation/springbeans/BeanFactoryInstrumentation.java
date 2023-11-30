package datadog.trace.instrumentation.springbeans;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Bean Factories can be set with a "beanClassloader" that is different from the injected
 * classloader. This leads to a ClassNotFoundException at runtime.
 *
 * <p>This instrumentation ensures class lookups for HandlerMappingResourceNameFilter and similar
 * contributions don't fail by manually setting the class on the bean definition
 *
 * <p>This can't be done at BeanDefinition construction time because Spring heavy use of clone()
 * which sometimes only copies the classname
 */
@AutoService(Instrumenter.class)
public class BeanFactoryInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
  public BeanFactoryInstrumentation() {
    super("spring-beans");
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
    return new String[] {packageName + ".BeanDefinitionRepairer"};
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("registerBeanDefinition"))
            .and(
                takesArgument(1, named("org.springframework.beans.factory.config.BeanDefinition"))),
        BeanFactoryInstrumentation.class.getName() + "$BeanRegisteringAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("resolveBeanClass"))
            .and(
                takesArgument(
                    0, named("org.springframework.beans.factory.support.RootBeanDefinition"))),
        BeanFactoryInstrumentation.class.getName() + "$BeanResolvingAdvice");
  }

  public static class BeanRegisteringAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(1) final BeanDefinition beanDefinition) {
      String className = beanDefinition.getBeanClassName();
      if (null != className
          && className.startsWith("datadog.trace.instrumentation.")
          && beanDefinition instanceof AbstractBeanDefinition
          && ((AbstractBeanDefinition) beanDefinition).hasBeanClass()) {
        BeanDefinitionRepairer.register(((AbstractBeanDefinition) beanDefinition).getBeanClass());
      }
    }
  }

  public static class BeanResolvingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final RootBeanDefinition beanDefinition) {
      if (!beanDefinition.hasBeanClass()) {
        BeanDefinitionRepairer.repair(beanDefinition);
      }
    }
  }
}
