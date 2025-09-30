package datadog.trace.instrumentation.springweb6.boot

import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
    http
      .csrf().disable()
      .headers().addHeaderWriter(
      new StaticHeadersWriter(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE))
      .and()
      .formLogin()
      .and()
      .httpBasic()
      .and()
      .authorizeHttpRequests()
      .requestMatchers("/secure/**").authenticated()
      .anyRequest().anonymous()
      .and().authenticationProvider(savingAuthenticationProvider())
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
