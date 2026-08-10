package datadog.smoketest.apmtracingdisabled;

import java.util.EnumSet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.SessionTrackingMode;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
  @Bean
  public ServletContextInitializer servletContextInitializer() {
    return new SessionTrackingConfig();
  }

  private class SessionTrackingConfig implements ServletContextInitializer {
    @Override
    public void onStartup(ServletContext servletContext) throws ServletException {
      EnumSet<SessionTrackingMode> sessionTrackingModes = EnumSet.of(SessionTrackingMode.COOKIE);
      servletContext.setSessionTrackingModes(sessionTrackingModes);
    }
  }
}
