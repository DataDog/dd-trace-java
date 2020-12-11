package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
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
public class BeanFactoryInstrumentation extends Instrumenter.Tracing {
  public BeanFactoryInstrumentation() {
    super("spring-web");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.springframework.beans.factory.support.AbstractBeanFactory");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return extendsClass(named("org.springframework.beans.factory.support.AbstractBeanFactory"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebHttpServerDecorator",
      packageName + ".SpringWebHttpServerDecorator$1",
      packageName + ".ServletRequestURIAdapter",
      packageName + ".HandlerMappingResourceNameFilter",
      packageName + ".HandlerMappingResourceNameFilter$BeanDefinition",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {

    return singletonMap(
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
