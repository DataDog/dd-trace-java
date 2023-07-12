package datadog.smoketest.springboot.jwt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextPersistenceFilter;

@Configuration
@EnableWebSecurity
public class JwtSecurityConfigurerAdapter extends WebSecurityConfigurerAdapter {

  protected void configure(HttpSecurity http) throws Exception {
    http.addFilterAfter(new JwtAuthenticationFilter(), SecurityContextPersistenceFilter.class)
        .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()
        .authorizeRequests()
        .mvcMatchers("/jwt")
        .authenticated()
        .and()
        .csrf()
        .disable()
        .httpBasic()
        .disable()
        .exceptionHandling()
        .authenticationEntryPoint(
            (request, response, authException) -> {
              response.sendError(401, authException.getMessage());
            })
        .accessDeniedHandler(
            (request, response, accessDeniedException) -> {
              response.sendError(403, "Access Denied.");
            })
        .and()
        .headers()
        .disable();
  }

  @Autowired
  public void configureGlobal(
      AuthenticationManagerBuilder auth,
      AuthenticationProvider authenticationProvider,
      UserDetailsService userDetailsService)
      throws Exception {
    auth.authenticationProvider(authenticationProvider).userDetailsService(userDetailsService);
  }

  @Bean
  public AuthenticationProvider jwtAuthenticationProvider(UserDetailsService userDetailsService)
      throws Exception {
    return new JwtAuthenticationProvider(userDetailsService);
  }

  @Bean
  public UserDetailsService jwtUserDetailsService() {
    return new JwtUserService();
  }

  @Override
  public AuthenticationManager authenticationManagerBean() throws Exception {
    return super.authenticationManagerBean();
  }
}
