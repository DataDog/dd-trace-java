package datadog.trace.instrumentation.springweb6.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // Lambda DSL works in both Spring Security 6 (Boot 3) and Spring Security 7 (Boot 4).
    // The chained no-arg variant was removed in Spring Security 7.
    http
      .csrf(csrf -> csrf.disable())
      .headers(headers -> headers.addHeaderWriter(
        new StaticHeadersWriter("x-ig-response-header", "ig-response-header-value")))
      .formLogin(Customizer.withDefaults())
      .httpBasic(Customizer.withDefaults())
      .authorizeHttpRequests(authz -> authz
        .requestMatchers("/secure/**").authenticated()
        .anyRequest().anonymous())
      .authenticationProvider(savingAuthenticationProvider());
    return http.build();
  }

  @Bean
  public SavingAuthenticationProvider savingAuthenticationProvider() {
    return new SavingAuthenticationProvider();
  }

  @Bean
  public HttpFirewall allowSemicolon() {
    StrictHttpFirewall firewall = new StrictHttpFirewall();
    firewall.setAllowSemicolon(true);
    return firewall;
  }
}
