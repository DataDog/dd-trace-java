package datadog.trace.instrumentation.springsecurity5

import custom.CustomAuthenticationFilter
import custom.CustomAuthenticationProvider
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer
import org.springframework.security.provisioning.JdbcUserDetailsManager
import org.springframework.security.provisioning.UserDetailsManager
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

import javax.sql.DataSource

import static datadog.trace.instrumentation.springsecurity5.SecurityConfig.CustomDsl.customDsl

@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.apply(customDsl())
    http
    .authorizeHttpRequests(
    (requests) -> requests
    .requestMatchers("/", "/success", "/register", "/login", "/custom").permitAll()
    .anyRequest().authenticated())
    .csrf().disable()
    .formLogin((form) -> form.loginPage("/login").permitAll())
    return http.build()
  }

  @Bean
  DataSource getDataSource() {
    DataSourceBuilder dataSourceBuilder = DataSourceBuilder.create()
    dataSourceBuilder.driverClassName("org.h2.Driver")
    dataSourceBuilder.url("jdbc:h2:mem:authDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;INIT=RUNSCRIPT FROM 'classpath:schema.sql';")
    dataSourceBuilder.username("SA")
    dataSourceBuilder.password("")
    return dataSourceBuilder.build()
  }

  @Bean
  UserDetailsManager userDetailsService() {
    return new JdbcUserDetailsManager(dataSource)
  }

  static class CustomDsl extends AbstractHttpConfigurer<CustomDsl, HttpSecurity> {
    @Override
    void configure(HttpSecurity http) throws Exception {
      AuthenticationManager authenticationManager = http.getSharedObject(AuthenticationManager)
      http.authenticationProvider(new CustomAuthenticationProvider())
      http.addFilterBefore(new CustomAuthenticationFilter(authenticationManager), UsernamePasswordAuthenticationFilter)
    }

    static CustomDsl customDsl() {
      return new CustomDsl()
    }
  }
}
