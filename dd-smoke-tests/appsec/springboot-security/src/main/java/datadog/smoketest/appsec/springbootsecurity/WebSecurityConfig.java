package datadog.smoketest.appsec.springbootsecurity;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.LogoutConfigurer;
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
  public UserDetailsManager userDetailsService() {
    return new JdbcUserDetailsManager(dataSource);
  }
}
