package custom;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

public class FailingAuthenticationProvider implements AuthenticationProvider {

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    throw new AuthenticationServiceException("I'm dumb");
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return true;
  }
}
