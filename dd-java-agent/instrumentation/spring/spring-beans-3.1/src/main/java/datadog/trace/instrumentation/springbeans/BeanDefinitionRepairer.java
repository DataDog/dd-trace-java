package datadog.trace.instrumentation.springbeans;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.support.RootBeanDefinition;

/**
 * Repairs Datadog injected bean definitions by restoring missing class references at resolve time.
 */
public final class BeanDefinitionRepairer {
  private static final Map<String, Class<?>> ddBeanClasses = new ConcurrentHashMap<>();

  public static void register(Class<?> beanClass) {
    ddBeanClasses.put(beanClass.getName(), beanClass);
  }

  public static void repair(RootBeanDefinition beanDefinition) {
    String className = beanDefinition.getBeanClassName();
    if (null != className) {
      Class<?> beanClass = ddBeanClasses.get(className);
      if (null != beanClass) {
        beanDefinition.setBeanClass(beanClass);
      }
    }
  }
}
