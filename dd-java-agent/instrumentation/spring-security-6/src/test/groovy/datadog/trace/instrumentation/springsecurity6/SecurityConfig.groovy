package datadog.trace.instrumentation.springsecurity6

import custom.CustomAuthenticationFilter
import custom.CustomAuthenticationProvider
import custom.FailingAuthenticationProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.provisioning.InMemoryUserDetailsManager
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.apply(CustomDsl.customDsl())
    http
    .csrf().disable()
    .formLogin((form) -> form.loginPage("/login").permitAll())
    .authorizeHttpRequests()
    .requestMatchers("/", "/success", "/register", "/login", "/custom").permitAll()
    .anyRequest().authenticated()
    return http.build()
  }

  @Bean
  UserDetailsManager userDetailsService() {
    return new InMemoryUserDetailsManager() {
      @Override
      void createUser(UserDetails user) {
        if (user.username == 'cant_create_me') {
          throw new IllegalArgumentException('cannot create user')
        }
        super.createUser(user)
      }
    }
  }

  static class CustomDsl extends AbstractHttpConfigurer<CustomDsl, HttpSecurity> {
    @Override
    void configure(HttpSecurity http) throws Exception {
      AuthenticationManager authenticationManager = http.getSharedObject(AuthenticationManager)
      http.authenticationProvider(new FailingAuthenticationProvider())
      http.authenticationProvider(new CustomAuthenticationProvider())
      http.addFilterBefore(new CustomAuthenticationFilter(authenticationManager), UsernamePasswordAuthenticationFilter)
    }

    static CustomDsl customDsl() {
      return new CustomDsl()
    }
  }
}
