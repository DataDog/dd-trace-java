package com.example.jwt;

import static org.springframework.security.oauth2.core.OAuth2TokenIntrospectionClaimNames.AUD;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Vector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
    return httpSecurity
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .mvcMatchers("/read/**")
                    .hasAuthority("SCOPE_read")
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt)
        .build();
  }

  RSAPublicKey buildKey() {
    try {
      String publicKeyStr = System.getProperty("publickey");
      System.out.println("Reading Public key: " + publicKeyStr);
      byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
      KeyFactory publicKeyFactory = KeyFactory.getInstance("RSA");
      EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
      return (RSAPublicKey) publicKeyFactory.generatePublic(publicKeySpec);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Bean
  public JwtDecoder jwtDecoder() {

    final NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(buildKey()).build();
    decoder.setJwtValidator(tokenValidator());
    return decoder;
  }

  public OAuth2TokenValidator<Jwt> tokenValidator() {
    final List<OAuth2TokenValidator<Jwt>> validators = new Vector<>();
    validators.add(new JwtTimestampValidator());
    validators.add(new JwtIssuerValidator("http://foobar.com"));
    validators.add(audienceValidator());
    return new DelegatingOAuth2TokenValidator<>(validators);
  }

  public OAuth2TokenValidator<Jwt> audienceValidator() {
    return new JwtClaimValidator<List<String>>(AUD, aud -> aud.contains("foobar"));
  }

  @Bean
  @Profile("roles")
  public JwtAuthenticationConverter jwtAuthenticationConverter() {
    final JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter =
        new JwtGrantedAuthoritiesConverter();
    grantedAuthoritiesConverter.setAuthoritiesClaimName("authorities");
    grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

    final JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
    jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
    return jwtAuthenticationConverter;
  }
}
