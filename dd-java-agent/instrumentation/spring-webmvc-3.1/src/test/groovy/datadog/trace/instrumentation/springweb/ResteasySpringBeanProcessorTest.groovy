package datadog.trace.instrumentation.springweb

import datadog.trace.agent.test.AgentTestRunner
import org.jboss.resteasy.plugins.spring.SpringBeanProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition

import javax.ws.rs.ext.Provider

class ResteasySpringBeanProcessorTest extends AgentTestRunner {

  @Provider
  class TestBean {}

  def "resteasy-spring should ignore our intercepting filter bean"() {
    given:
    // mimic Spring's use of clone which loses the original 'beanClass' value
    def filterDefinition = new HandlerMappingResourceNameFilter.BeanDefinition().clone()
    // simple test bean to make sure resteasy isn't ignoring all bean definitions
    def testDefinition = Mock(GenericBeanDefinition)
    testDefinition.getBeanClassName() >> TestBean.name
    testDefinition.isSingleton() >> true

    and:
    def beanFactory = Mock(ConfigurableListableBeanFactory)
    beanFactory.getBeanDefinitionNames() >> ['filter', 'test']
    beanFactory.getBeanDefinition('filter') >> filterDefinition
    beanFactory.getBeanDefinition('test') >> testDefinition

    and:
    def originalContext = Thread.currentThread().contextClassLoader
    // mimic isolated setup where our filter bean is not visible to the resteasy-spring context
    def isolatedContext = new ClassLoader(null) {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
          if (name == TestBean.name) {
            return TestBean
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
