package com.example.jwt;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class Token {
  Jwt token;
  Collection<GrantedAuthority> authorities;

  public Token(Jwt token, Collection<GrantedAuthority> authorities) {
    this.token = token;
    this.authorities = authorities;
  }

  public Jwt token() {
    return token;
  }

  public Collection<GrantedAuthority> authorities() {
    return authorities;
  }
}
