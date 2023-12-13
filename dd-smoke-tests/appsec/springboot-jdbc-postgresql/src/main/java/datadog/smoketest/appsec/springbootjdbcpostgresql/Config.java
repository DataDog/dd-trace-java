package datadog.smoketest.appsec.springbootjdbcpostgresql;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class Config {

  private final String DB_URL;

  public Config() {
    String url = System.getenv("DB_URL");
    if (url == null) {
      url = "postgresql://localhost:5432/testdb";
    }
    this.DB_URL = url;
  }

  @Bean
  public DataSource dataSource() {
    DriverManagerDataSource source = new DriverManagerDataSource();
    source.setDriverClassName("org.postgresql.Driver");
    source.setUrl("jdbc:" + DB_URL);
    String USER_NAME = "postgres";
    source.setUsername(USER_NAME);
    String PASSWORD = "postgres";
    source.setPassword(PASSWORD);
    return source;
  }

  @Bean
  @DependsOn("dataSource")
  public JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }
}
