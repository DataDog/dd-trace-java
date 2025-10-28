package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.config.BeanDefinition;

/**
 * Resteasy's SpringBeanProcessor attempts to load our {@link HandlerMappingResourceNameFilter} bean
 * definition before it is resolved. In modular environments such as jboss-modules this can lead to
 * a {@link ClassNotFoundException}. This bean is not of interest to Resteasy because it is not a
 * provider or a root resource, so it is safe to skip processing of it in the 'processBean' method.
 */
@AutoService(InstrumenterModule.class)
public class SpringBeanProcessorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public SpringBeanProcessorInstrumentation() {
    super("spring-web");
  }

  @Override
  public String instrumentedType() {
    return "org.jboss.resteasy.plugins.spring.SpringBeanProcessor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("processBean"))
            .and(
                takesArgument(3, named("org.springframework.beans.factory.config.BeanDefinition"))),
        SpringBeanProcessorInstrumentation.class.getName() + "$SkipBeanClassAdvice");
  }

  @Override
  public String muzzleDirective() {
    return "resteasy-spring";
  }

  public static class SkipBeanClassAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static Class<?> onEnter(@Advice.Argument(3) final BeanDefinition beanDefinition) {
      String className = beanDefinition.getBeanClassName();
      if (null != className && className.startsWith("datadog.trace.instrumentation.")) {
        return Object.class; // skip the original method and return this value instead
      } else {
        return null; // continue on to call the original method and return its value
      }
    }
  }
}
