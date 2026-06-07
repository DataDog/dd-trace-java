package datadog.trace.instrumentation.springweb6.boot

import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.security.web.header.writers.StaticHeadersWriter

@Configuration
@EnableWebSecurity
class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // Use the lambda DSL so this fixture compiles and runs against both Spring Security 6
    // (Spring Boot 3) and Spring Security 7 (Spring Boot 4). The chained no-arg DSL
    // (csrf().disable().and()...) was removed in Spring Security 7. The Groovy closures are
    // coerced to Customizer instances.
    http
      .csrf({ it.disable() })
      .headers({ headers ->
        headers.addHeaderWriter(
          new StaticHeadersWriter(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE))
      })
      .formLogin(Customizer.withDefaults())
      .httpBasic(Customizer.withDefaults())
      .authorizeHttpRequests({ authz ->
        authz.requestMatchers("/secure/**").authenticated()
          .anyRequest().anonymous()
      })
      .authenticationProvider(savingAuthenticationProvider())
      .build()
  }

  @Bean
  SavingAuthenticationProvider savingAuthenticationProvider() {
    return new SavingAuthenticationProvider()
  }

  @Bean
  HttpFirewall allowSemicolon() {
    new StrictHttpFirewall(allowSemicolon: true)
  }
}
