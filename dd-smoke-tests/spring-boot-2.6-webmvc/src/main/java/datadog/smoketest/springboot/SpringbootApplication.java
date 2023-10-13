package datadog.smoketest.springboot;

import datadog.smoketest.springboot.controller.SimpleIastController;
import java.util.Collections;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.mvc.Controller;
import org.springframework.web.util.UrlPathHelper;

@SpringBootApplication
@EnableJpaRepositories
public class SpringbootApplication {

  @Configuration
  @ComponentScan(basePackages = {"datadog.smoketest.springboot.controller"})
  public static class WebConfig extends WebMvcConfigurerAdapter {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
      UrlPathHelper urlPathHelper = new UrlPathHelper();
      urlPathHelper.setRemoveSemicolonContent(false);
      configurer.setUrlPathHelper(urlPathHelper);
    }

    @Bean
    public Controller simpleIastController() {
      return new SimpleIastController();
    }

    @Bean
    public SimpleUrlHandlerMapping simpleMapping(Controller simpleIastController) {
      SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
      mapping.setUrlMap(Collections.singletonMap("/simple/{var1}", simpleIastController));
      mapping.setOrder(0);
      return mapping;
    }
  }

  public static void main(final String[] args) {
    SpringApplication.run(SpringbootApplication.class, args);
  }
}
