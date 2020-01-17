package dd.trace.instrumentation.springsecurity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDecisionManager;
import org.springframework.security.access.AccessDecisionVoter;
import org.springframework.security.access.vote.AffirmativeBased;
import org.springframework.security.access.vote.RoleVoter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.crypto.password.LdapShaPasswordEncoder;
import org.springframework.security.web.access.expression.WebExpressionVoter;

import java.util.Arrays;
import java.util.List;

//spring 3.2.0
//https://github.com/spring-projects/spring-security/blob/3.2.0.RELEASE/core/src/main/java/org/springframework/security/authentication/encoding/LdapShaPasswordEncoder.java
//spring 5.2.0
//https://github.com/spring-projects/spring-security/tree/5.2.0.RELEASE/crypto/src/main/java/org/springframework/security/crypto/password
//https://github.com/spring-projects/spring-security/commit/3a4a32e654dda7dec5f6908d8f77df028e9cbdd3
//https://github.com/spring-projects/spring-security/blob/4.2.13.RELEASE/ldap/src/main/java/org/springframework/security/ldap/authentication/PasswordComparisonAuthenticator.java

@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

  @Bean
  public AccessDecisionManager accessDecisionManager() {
    List<AccessDecisionVoter<? extends Object>> decisionVoters =
        Arrays.asList(new WebExpressionVoter(), new RoleVoter());
    AffirmativeBased ab = new AffirmativeBased(decisionVoters);
    return ab;
  }

  @Bean
  public AuthenticationManager customAuthenticationManager() throws Exception {
    return authenticationManager();
  }

  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http.csrf()
        .disable()
        .authorizeRequests()
        .anyRequest()
        .authenticated()
        .accessDecisionManager(accessDecisionManager())
        .and()
        .formLogin();
  }

  @Override
  public void configure(AuthenticationManagerBuilder auth) throws Exception {
    auth.ldapAuthentication()
        .userDnPatterns("uid={0},ou=people")
        .groupSearchBase("ou=groups")
        .contextSource()
        .url("ldap://localhost:8389/dc=springframework,dc=org")
        .and()
        .passwordCompare()
        .passwordEncoder(new LdapShaPasswordEncoder())
        .passwordAttribute("userPassword");
  }
}
