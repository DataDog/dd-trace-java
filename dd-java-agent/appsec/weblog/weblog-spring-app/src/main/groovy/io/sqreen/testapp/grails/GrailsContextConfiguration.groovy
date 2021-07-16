package io.sqreen.testapp.grails

import grails.boot.config.GrailsApplicationPostProcessor
import grails.core.GrailsApplication
import grails.web.mapping.LinkGenerator
import org.grails.gsp.io.GroovyPageScriptSource
import org.grails.web.gsp.io.GrailsConventionGroovyPageLocator
import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.config.RuntimeBeanReference
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/* in a different package to avoid it being scanned as part of the WebApplicationContext */
@Configuration
class GrailsContextConfiguration {

  @Bean
  GrailsConventionGroovyPageLocator grailsConventionGroovyPageLocator() {
    new GrailsConventionGroovyPageLocator() {
        @Override
        protected List<String> resolveSearchPaths(String uri) {
          ["classpath:/gsp/$uri" as String]
        }

        @Override
        GroovyPageScriptSource findViewByPath(String uri) {
          uri = "${uri}.gsp"
          return findPage(uri)
        }
      }
  }

  @Bean
  static BeanFactoryPostProcessor replaceGroovyPageLocator() {
    // we need to change the groovy page locator
    // simply overriding the groovyPageLocator bean is alas not sufficient, as bean defined by
    // Grails has priority and will override ours instead
    new BeanFactoryPostProcessor() {
        @Override
        void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
          /* Note that jspViewResolver is actually a bean of type GroovyPageViewResolver,
         * as defined in GroovyPagesGrailsPlugin */
          def beansToChange = ['jspViewResolver', 'groovyPagesTemplateEngine']
          beansToChange.each { beanName ->
            def values = beanFactory.getBeanDefinition(beanName).propertyValues
            values.removePropertyValue('groovyPageLocator')
            values.addPropertyValue('groovyPageLocator',
              new RuntimeBeanReference('grailsConventionGroovyPageLocator'))
          }

          beanFactory.removeBeanDefinition('groovyPageLocator')
        }
      }
  }

  @Bean
  static BeanFactoryPostProcessor addServletContext() {
    new BeanFactoryPostProcessor() {
        @Override
        void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
          def definition = beanFactory.getBeanDefinition('jspViewResolver')
          def values1 = definition.propertyValues
          values1.addPropertyValue('servletContext', new RuntimeBeanReference('servletContext'))
        }
      }
  }

  @Bean
  GrailsApplication grailsApplication() {
    //new DefaultGrailsApplication()
    grailsApplicationPostProcessor(null).grailsApplication
  }

  @Bean
  static GrailsApplicationPostProcessor grailsApplicationPostProcessor(ApplicationContext ctx) {
    def gapp = new GrailsApplicationPostProcessor(null, ctx)
    gapp.grailsApplication.config.merge(
      'grails.gsp.view.layoutViewResolver': false,
      )
    gapp
  }

  @Bean
  LinkGenerator fakeLinkGenerator() {
    [:] as LinkGenerator
  }
}
