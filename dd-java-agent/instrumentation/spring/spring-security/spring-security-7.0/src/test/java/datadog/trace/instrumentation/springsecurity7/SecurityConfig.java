package datadog.trace.instrumentation.springsecurity7;

import custom.CustomAuthenticationFilter;
import custom.CustomAuthenticationProvider;
import custom.FailingAuthenticationProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .formLogin(form -> form.loginPage("/login").permitAll())
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/", "/success", "/register", "/login", "/custom")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .with(
            new CustomSecurityConfigurer(),
            configurer -> {
              // no additional customization needed
            });
    return http.build();
  }

  @Bean
  public UserDetailsManager userDetailsService() {
    return new InMemoryUserDetailsManager() {
      @Override
      public void createUser(UserDetails user) {
        if ("cant_create_me".equals(user.getUsername())) {
          throw new IllegalArgumentException("cannot create user");
        }
        super.createUser(user);
      }
    };
  }

  /**
   * Spring Security 7 replaces {@code http.apply()} with {@code http.with()} for custom
   * configurers. This configurer registers the custom authentication filter and providers used by
   * the skipped-authentication tests.
   */
  static class CustomSecurityConfigurer
      extends org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer<
          CustomSecurityConfigurer, HttpSecurity> {

    @Override
    public void configure(HttpSecurity http) {
      AuthenticationManager authenticationManager =
          http.getSharedObject(AuthenticationManager.class);
      http.authenticationProvider(new FailingAuthenticationProvider());
      http.authenticationProvider(new CustomAuthenticationProvider());
      http.addFilterBefore(
          new CustomAuthenticationFilter(authenticationManager),
          UsernamePasswordAuthenticationFilter.class);
    }
  }
}
