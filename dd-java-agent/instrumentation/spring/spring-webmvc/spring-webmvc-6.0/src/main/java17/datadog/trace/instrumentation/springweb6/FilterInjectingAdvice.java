package datadog.trace.instrumentation.springweb6;

import net.bytebuddy.asm.Advice;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

public class FilterInjectingAdvice {
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
