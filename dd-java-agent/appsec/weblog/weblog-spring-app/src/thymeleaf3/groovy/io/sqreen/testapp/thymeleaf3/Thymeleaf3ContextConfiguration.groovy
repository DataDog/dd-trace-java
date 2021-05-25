package io.sqreen.testapp.thymeleaf3

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.View
import org.springframework.web.servlet.ViewResolver
import org.thymeleaf.ITemplateEngine
import org.thymeleaf.TemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.templateresolver.ITemplateResolver

@Configuration
class Thymeleaf3ContextConfiguration {

  @Bean
  ITemplateEngine templateEngine() {
    ITemplateResolver templateResolver = new ClassLoaderTemplateResolver(
      templateMode: TemplateMode.HTML,
      prefix: 'templates/',
      suffix: '.html',
      cacheable: false,
      )
    new TemplateEngine(templateResolver: templateResolver)
  }

  @Bean
  ViewResolver viewResolver(ITemplateEngine engine) {
    new ViewResolver() {
        @Override
        View resolveViewName(String viewName, Locale locale) throws Exception {
          new Thymeleaf3View(templateEngine: engine, locale: locale, viewName: viewName)
        }
      }
  }
}
