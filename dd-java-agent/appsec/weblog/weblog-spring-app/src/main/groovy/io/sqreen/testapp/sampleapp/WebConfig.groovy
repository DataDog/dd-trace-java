package io.sqreen.testapp.sampleapp

import io.sqreen.testapp.grails.GrailsContextConfiguration
import org.apache.velocity.tools.generic.EscapeTool
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.PropertySource
import org.springframework.core.Ordered
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.web.multipart.commons.CommonsMultipartResolver
import org.springframework.web.servlet.View
import org.springframework.web.servlet.ViewResolver
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter
import org.springframework.web.servlet.view.InternalResourceViewResolver
import org.springframework.web.servlet.view.freemarker.FreeMarkerViewResolver
import org.springframework.web.servlet.view.groovy.GroovyMarkupViewResolver
import org.springframework.web.servlet.view.velocity.VelocityConfigurer
import org.springframework.web.servlet.view.velocity.VelocityViewResolver
import org.thymeleaf.dialect.IDialect
import org.thymeleaf.extras.springsecurity3.dialect.SpringSecurityDialect
import org.thymeleaf.spring4.view.ThymeleafViewResolver

@Configuration
@ComponentScan
@PropertySource('classpath:application.properties')
class WebConfig extends WebMvcConfigurerAdapter {

  // required for multipart file upload
  @Bean
  CommonsMultipartResolver multipartResolver() {
    CommonsMultipartResolver multipartResolver = new CommonsMultipartResolver()
    multipartResolver.setMaxUploadSize(-1)
    multipartResolver
  }

  @Override
  void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController('/').viewName = 'index'
    registry.addViewController('/login/').viewName = 'login'
  }

  @Bean
  IDialect springSecurityDialect() {
    new SpringSecurityDialect()
  }

  @Bean
  FilterRegistrationBean testFilter() {
    new FilterRegistrationBean(
      filter: new TestFilter(),
      urlPatterns: ['/test_filter'])
  }

  // replaces the 'viewResolver' bean, which is by default a ContentNegotiatingBeanResolver with highest precedence
  @Bean
  ViewResolver viewResolver(FreeMarkerViewResolver freeMarkerViewResolver,
    ThymeleafViewResolver thymeleafViewResolver,
    GroovyMarkupViewResolver groovyMarkupViewResolver,
    @Qualifier('defaultViewResolver') InternalResourceViewResolver jspViewResolver,
    @Qualifier('groovyPageViewResolver') ViewResolver groovyPageViewResolver
  ) {
    new ViewNameSuffixViewResolver(
      order: Ordered.HIGHEST_PRECEDENCE,
      delegates: [
        '.ftl': freeMarkerViewResolver,
        '.tpl': groovyMarkupViewResolver,
        '.html': thymeleafViewResolver,
        '.tl3': thymeleaf3ViewResolver(),
        '.jsp': jspViewResolver,
        '.gsp': groovyPageViewResolver,
        '.vm': velocityViewResolver(),
      ],
      fallback: thymeleafViewResolver)
  }

  private static class ViewNameSuffixViewResolver implements ViewResolver, Ordered {
    Map<String, ViewResolver> delegates
    ViewResolver fallback
    int order

    @Override
    View resolveViewName(String viewName, Locale locale) throws Exception {
      if (viewName == 'error') {
        return null // don't render error view, leave to default behavior
      }

      for (Map.Entry<String, ViewResolver> r: delegates) {
        String suffix = r.key
        ViewResolver resolver = r.value

        if (viewName.endsWith(suffix)) {
          return resolver.resolveViewName(viewName[0..<-suffix.size()], locale)
        }
      }

      fallback.resolveViewName(viewName, locale)
    }
  }


  @Bean
  ViewResolver velocityViewResolver() {
    new VelocityViewResolver(
      suffix: '.vm',
      cache: false,
      order: Ordered.LOWEST_PRECEDENCE,
      contentType: 'text/html;charset=UTF-8',
      attributesMap: [
        esc: new EscapeTool(),
      ]
      )
  }

  @Bean
  VelocityConfigurer velocityConfigurer() {
    new VelocityConfigurer(
      resourceLoaderPath: 'classpath:/velocity/',
      )
  }

  /* lazy load the groovy page resolver in a separate application context to avoid it dragging the startup */
  @Bean
  ViewResolver groovyPageViewResolver(ApplicationContext context) {
    new ViewResolver() {
        @Override
        View resolveViewName(String viewName, Locale locale) throws Exception {
          context.getBean('grailsApplicationContext').getBean(GroovyPageViewResolver).
            resolveViewName(viewName, locale)
        }
      }
  }

  @Bean
  @Lazy
  BeanFactory grailsApplicationContext(ApplicationContext applicationContext) {
    def context = new AnnotationConfigApplicationContext(parent: applicationContext)
    context.register(GrailsContextConfiguration)
    context.refresh()
    context
  }

  @Bean
  @Lazy
  BeanFactory thymeleaf3ModuleContext(ApplicationContext parent) {
    createModuleAppCtx(parent, 'thymeleaf3')
  }

  @Bean
  ViewResolver thymeleaf3ViewResolver() {
    new ViewResolver() {
        @Override
        View resolveViewName(String viewName, Locale locale) throws Exception {
          thymeleaf3ModuleContext(null).getBean('viewResolver')
            .resolveViewName(viewName, locale)
        }
      }
  }

  private AnnotationConfigApplicationContext createModuleAppCtx(ApplicationContext parent,
    String moduleName) {
    def modCl = createModuleClassloader(parent.classLoader, moduleName)
    def context = new AnnotationConfigApplicationContext(
      parent: parent,
      displayName: "Module $moduleName",
      classLoader: modCl,
      )
    context.scan("io.sqreen.testapp.$moduleName")
    context.refresh()
    context
  }

  private ClassLoader createModuleClassloader(ClassLoader parent, String moduleName) {
    String base = "modules/$moduleName"
    def allResources = new PathMatchingResourcePatternResolver().getResources("$base/*.jar") as List
    def classpath = [new ClassPathResource("$base/", parent).URL, *(allResources*.URL)
    ] as URL[]
    new ChildFirstURLClassLoader(classpath, parent)
  }
}
