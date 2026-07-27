package custom;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

public class CustomAuthenticationProvider implements AuthenticationProvider {

  @Override
  public Authentication authenticate(Authentication authentication) throws AuthenticationException {
    return authentication;
  }

  @Override
  public boolean supports(Class<?> authentication) {
    return CustomAuthenticationToken.class.isAssignableFrom(authentication);
  }
}
