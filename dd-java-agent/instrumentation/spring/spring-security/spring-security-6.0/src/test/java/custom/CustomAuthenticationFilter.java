package custom;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;

public class CustomAuthenticationFilter extends AbstractAuthenticationProcessingFilter {

  private static final String HEADER_NAME = "X-Custom-User";

  private final AuthenticationManager authenticationManager;

  public CustomAuthenticationFilter(final AuthenticationManager authenticationManager) {
    super("/custom");
    this.authenticationManager = authenticationManager;
  }

  @Override
  public Authentication attemptAuthentication(
      HttpServletRequest request, HttpServletResponse response)
      throws AuthenticationException, IOException, ServletException {
    final String user = request.getHeader(HEADER_NAME);
    if (user == null) {
      return null;
    }
    return authenticationManager.authenticate(new CustomAuthenticationToken(user));
  }
}
