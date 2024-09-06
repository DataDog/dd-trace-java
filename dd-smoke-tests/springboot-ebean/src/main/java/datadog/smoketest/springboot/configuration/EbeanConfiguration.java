package datadog.smoketest.springboot.configuration;

import datadog.smoketest.springboot.service.CurrentUser;
import io.ebean.Database;
import io.ebean.DatabaseFactory;
import io.ebean.config.DatabaseConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EbeanConfiguration {

  @Bean
  public Database database(CurrentUser currentUser) {
    DatabaseConfig config = new DatabaseConfig();
    // config.setName("db"); db is the default name
    config.setCurrentUserProvider(currentUser);
    config.loadFromProperties();
    return DatabaseFactory.create(config);
  }
}
