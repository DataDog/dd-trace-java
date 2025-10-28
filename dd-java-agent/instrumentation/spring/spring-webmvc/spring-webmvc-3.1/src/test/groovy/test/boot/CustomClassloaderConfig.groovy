package test.boot

import groovy.transform.CompileStatic
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Sets the bean classloader to one that explicitly filters datadog classes
 * The goal is to test a classloader that we didn't inject.  With our test
 * setup, its easier to filter than create a new one
 */
@Configuration
class CustomClassloaderConfig {

  @Bean
  BeanFactoryPostProcessor postProcessor() {
    return new BeanFactoryPostProcessor() {
        @Override
        void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
          beanFactory.setBeanClassLoader(new DatadogFilteringClassloader(this.getClass().getClassLoader()))
        }
      }
  }
}

@CompileStatic // avoid references to java.lang.Module (in super$2$loadClass)
class DatadogFilteringClassloader extends URLClassLoader {
  DatadogFilteringClassloader(ClassLoader parent) {
    super(new URL[0], parent)
  }

  protected Class<?> loadClass(String className, boolean resolve)
  throws ClassNotFoundException {
    if (className.startsWith("datadog")) {
      throw new ClassNotFoundException()
    }

    return super.loadClass(className, resolve)
  }
}
