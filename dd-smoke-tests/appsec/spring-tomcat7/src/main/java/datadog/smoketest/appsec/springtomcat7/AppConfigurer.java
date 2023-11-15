package datadog.smoketest.appsec.springtomcat7;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

@Configuration
@EnableWebMvc
@ComponentScan(basePackages = {"datadog.smoketest.appsec.springtomcat7"})
public class AppConfigurer extends WebMvcConfigurerAdapter {}
