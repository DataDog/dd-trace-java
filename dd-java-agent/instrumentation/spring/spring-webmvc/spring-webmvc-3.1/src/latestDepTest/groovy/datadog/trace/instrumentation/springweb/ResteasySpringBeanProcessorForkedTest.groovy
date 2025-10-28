package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.InstrumentationSpecification
import org.jboss.resteasy.plugins.spring.SpringBeanProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.GenericBeanDefinition
import test.boot.TestProvider

class ResteasySpringBeanProcessorForkedTest extends InstrumentationSpecification {

  interface TestBeanFactory extends ConfigurableListableBeanFactory, BeanDefinitionRegistry {}

  def "resteasy-spring should ignore our intercepting filter bean"() {
    given:
    def beanFactory = Mock(TestBeanFactory)
    def originalDefinition = new HandlerMappingResourceNameFilter.BeanDefinition()
    // trigger capture of the original 'beanClass' value
    beanFactory.registerBeanDefinition('filter', originalDefinition)
    // mimic situation where the original 'beanClass' value might be lost
    def filterDefinition = originalDefinition.clone()
    filterDefinition.setBeanClassName(originalDefinition.getBeanClassName()) // discards 'beanClass'
    // simple test bean to make sure resteasy isn't ignoring all bean definitions
    def testDefinition = Mock(GenericBeanDefinition)
    testDefinition.getBeanClassName() >> TestProvider.name
    testDefinition.isSingleton() >> true

    and:
    beanFactory.getBeanDefinitionNames() >> ['filter', 'test']
    beanFactory.getBeanDefinition('filter') >> filterDefinition
    beanFactory.getBeanDefinition('test') >> testDefinition

    and:
    def originalContext = Thread.currentThread().contextClassLoader
    // mimic isolated setup where our filter bean is not visible to the resteasy-spring context
    def isolatedContext = new ClassLoader(null) {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
          if (name == TestProvider.name) {
            return TestProvider
          }
          throw new ClassNotFoundException(name)
        }
      }

    and:
    def resteasyProcessor = new SpringBeanProcessor()

    when:
    Thread.currentThread().contextClassLoader = isolatedContext
    resteasyProcessor.postProcessBeanFactory(beanFactory)

    then:
    // should have skipped our filter bean, which is not of interest to resteasy
    noExceptionThrown()
    // simple test bean should still have been picked up
    resteasyProcessor.providerNames == ['test'] as Set

    cleanup:
    Thread.currentThread().contextClassLoader = originalContext
  }
}
