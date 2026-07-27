package custom;

import java.util.Collections;
import org.springframework.security.authentication.AbstractAuthenticationToken;

public class CustomAuthenticationToken extends AbstractAuthenticationToken {

  private final String user;

  public CustomAuthenticationToken(String user) {
    super(Collections.emptyList());
    this.user = user;
  }

  @Override
  public Object getCredentials() {
    return user;
  }

  @Override
  public Object getPrincipal() {
    return user;
  }
}
