package datadog.smoketest.springboot.jwt;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.GenericFilterBean;

public class JwtAuthenticationFilter extends GenericFilterBean {

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain)
      throws IOException, ServletException {
    String authorization = ((HttpServletRequest) request).getHeader("Authorization");
    if (authorization != null) {
      if (authorization.contains("Bearer ")) {
        authorization = authorization.replace("Bearer ", "").trim();
      }
      JwtAuthentication authentication = new JwtAuthentication(authorization);
      SecurityContextHolder.getContext().setAuthentication(authentication);
    }
    filterChain.doFilter(request, response);
  }
}
