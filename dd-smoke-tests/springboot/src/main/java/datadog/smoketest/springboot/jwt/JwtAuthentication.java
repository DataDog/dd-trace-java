package datadog.smoketest.springboot.jwt;

import com.auth0.jwt.interfaces.Payload;
import java.util.Collection;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class JwtAuthentication implements Authentication {
  private String token;
  private boolean isAuthenticated = false;
  private UserDetails user;
  private Payload payload;

  JwtAuthentication(String jwtToken) {
    this.token = jwtToken;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return user.getAuthorities();
  }

  @Override
  public Object getCredentials() {
    return token;
  }

  @Override
  public Object getDetails() {
    return this.user;
  }

  @Override
  public String getName() {
    return payload.getSubject();
  }

  @Override
  public Object getPrincipal() {
    return this.getName();
  }

  @Override
  public boolean isAuthenticated() {
    return this.isAuthenticated;
  }

  @Override
  public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
    this.isAuthenticated = isAuthenticated;
  }

  public void setUserDetails(UserDetails userDetails) {
    this.user = userDetails;
  }

  public void setPayload(Payload payload) {
    this.payload = payload;
  }
}
