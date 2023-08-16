package datadog.smoketest.appsec.springbootsecurity;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

  final DataSource dataSource;

  public WebSecurityConfig(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.authorizeRequests(
            (requests) ->
                requests
                    .antMatchers("/", "/signup", "/register")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .formLogin((form) -> form.loginPage("/login").permitAll())
        .logout(LogoutConfigurer::permitAll);

    return http.build();
  }

  @Bean
  @DependsOn("dataSource")
  public DataSourceInitializer dataSourceInitializer() {
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource("schema.sql"));

    DataSourceInitializer initializer = new DataSourceInitializer();
    initializer.setDataSource(dataSource);
    initializer.setDatabasePopulator(populator);

    return initializer;
  }

  @Bean
  @DependsOn("dataSourceInitializer")
  public UserDetailsManager userDetailsService() {
    UserDetailsManager userDetailsManager = new JdbcUserDetailsManager(dataSource);

    // Create some default for case when user creation happens outside request
    userDetailsManager.createUser(
        User.withUsername("default_user").password("{noop}").roles("USER").build());

    return userDetailsManager;
  }
}
