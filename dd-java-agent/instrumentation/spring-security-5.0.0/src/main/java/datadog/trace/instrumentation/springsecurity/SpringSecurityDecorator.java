package datadog.trace.instrumentation.springsecurity;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.security.web.FilterInvocation;

@Slf4j
public class SpringSecurityDecorator extends BaseDecorator {
  public static final String DELIMITER = ", ";
  public static final SpringSecurityDecorator DECORATOR = new SpringSecurityDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-security"};
  }

  @Override
  protected String component() {
    return "spring-security";
  }

  @Override
  protected boolean traceAnalyticsDefault() {
    return false;
  }

  @Override
  protected String spanType() {
    return null;
  }

  public void onConfigAttributes(
      final AgentSpan span, final Collection<ConfigAttribute> configAttributes) {
    String str = new String();

    for (final ConfigAttribute ca : configAttributes) {
      str += DELIMITER;
      final String attribute = ca.getAttribute();
      if (attribute != null) {
        str += attribute;
      } else {
        str += ca.toString();
      }
    }
    if (!str.isEmpty()) {
      span.setTag("config.attribute", str.substring(DELIMITER.length()));
    }
  }

  public void onSecuredObject(final AgentSpan span, final Object object) {
    String securedObject = null;
    if (object instanceof org.springframework.security.web.FilterInvocation) {
      final FilterInvocation fi = (FilterInvocation) object;
      securedObject = fi.getRequest().getRequestURL().toString();
    } else if (object instanceof org.springframework.security.util.SimpleMethodInvocation) {
      final SimpleMethodInvocation smi = (SimpleMethodInvocation) object;
      securedObject = smi.getMethod().getName();
    }
    span.setTag("secured.object", securedObject);
  }

  public void onAuthentication(final AgentSpan span, final Authentication auth) {
    if (auth == null) {
      return;
    }

    final Object principal = auth.getPrincipal();

    if (principal instanceof UserDetails) {

      final UserDetails ud = (UserDetails) principal;
      final String username = ud.getUsername();
      if (username != null && !username.isEmpty()) {
        span.setTag("auth.user.name", username);
      }
      final Boolean isAccountNonExpired = ud.isAccountNonExpired();
      span.setTag("auth.user.non_expired", isAccountNonExpired);
      final Boolean isAccountNonLocked = ud.isAccountNonLocked();
      span.setTag("auth.user.account_non_locked", isAccountNonLocked);
      final Boolean isCredentialsNonExpired = ud.isCredentialsNonExpired();
      span.setTag("auth.user.credentials_non_locked", isCredentialsNonExpired);

      final Collection<? extends GrantedAuthority> coll = ud.getAuthorities();
      String str = new String();
      for (final GrantedAuthority authority : coll) {
        str += DELIMITER;
        str += authority.getAuthority().toString();
      }
      if (coll.size() != 0) {
        span.setTag("auth.user.authorities", str.substring(DELIMITER.length()));
      }
    }

    span.setTag("auth.name", auth.getName());

    final boolean isAuthenticated = auth.isAuthenticated();
    span.setTag("auth.authenticated", isAuthenticated);
  }
}
