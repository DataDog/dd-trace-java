package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class FailOnHeaderConfig implements WebMvcConfigurer {
  @Override
  public void addInterceptors(final InterceptorRegistry registry) {
    registry.addInterceptor(new FailOnHeaderInterceptor());
  }
}
