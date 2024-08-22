package datadog.smoketest.springboot.config;

import datadog.smoketest.springboot.servlet.MultipartGetItemIteratorServlet;
import datadog.smoketest.springboot.servlet.MultipartParseParameterMapServlet;
import datadog.smoketest.springboot.servlet.MultipartParseRequestServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IastWebConfig {

  @Bean
  public ServletRegistrationBean multipartParseRequestServlet() {
    return new ServletRegistrationBean(
        new MultipartParseRequestServlet(), "/untrusted_deserialization/parse_request");
  }

  @Bean
  public ServletRegistrationBean multipartParseParameterMapServlet() {
    return new ServletRegistrationBean(
        new MultipartParseParameterMapServlet(), "/untrusted_deserialization/parse_parameter_map");
  }

  @Bean
  public ServletRegistrationBean multipartGetItemIteratorServlet() {
    return new ServletRegistrationBean(
        new MultipartGetItemIteratorServlet(), "/untrusted_deserialization/get_item_iterator");
  }
}
