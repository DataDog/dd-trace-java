package test.boot

import datadog.trace.agent.test.base.HttpServerTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter
import org.springframework.security.web.firewall.HttpFirewall
import org.springframework.security.web.firewall.StrictHttpFirewall
import org.springframework.security.web.header.writers.StaticHeadersWriter

@Configuration
@EnableWebSecurity
class SecurityConfig extends WebSecurityConfigurerAdapter {

  @Override
  protected void configure(HttpSecurity http) throws Exception {

    http
      .csrf().disable()
      .formLogin()
      .and()
      .headers().addHeaderWriter(
      new StaticHeadersWriter(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE))
      .and()
      .authorizeRequests()
      .antMatchers("/secure/**").authenticated()
      .and().authenticationProvider(applicationContext.getBean(SavingAuthenticationProvider))
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
