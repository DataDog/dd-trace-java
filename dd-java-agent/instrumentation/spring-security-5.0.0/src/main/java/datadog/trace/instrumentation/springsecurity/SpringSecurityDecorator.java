package datadog.trace.instrumentation.springsecurity;

import datadog.trace.agent.decorator.BaseDecorator;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

@Slf4j
public class SpringSecurityDecorator extends BaseDecorator {
  public static final Logger LOGGER = LoggerFactory.getLogger(SpringSecurityDecorator.class);
  public static final String DELIMITER = ", ";
  public static final SpringSecurityDecorator DECORATOR = new SpringSecurityDecorator();
  private String securedObject;

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"spring-security"};
  }

  // @Override
  protected String service() {
    return "spring-security";
  }

  @Override
  protected String component() {
    return "spring-security";
  }

  @Override
  protected boolean traceAnalyticsDefault() {
    return false;
  }

  protected String spanType() {
    return DDSpanTypes.HTTP_SERVER;
  }

  public String securedObject() {
    return securedObject;
  }

  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    span.setTag(DDTags.SERVICE_NAME, service());
    return super.afterStart(span);
  }

  public void setTagsFromConfigAttributes(
      AgentSpan span, Collection<ConfigAttribute> configAttributes) {
    String str = new String();

    for (ConfigAttribute ca : configAttributes) {
      str += DELIMITER;
      String attribute = ca.getAttribute();
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

  public void setTagsFromSecuredObject(AgentSpan span, Object object) {
    if (object != null) {
      if (object instanceof org.springframework.security.web.FilterInvocation) {
        FilterInvocation fi = (FilterInvocation) object;
        securedObject = fi.getRequest().getRequestURL().toString();
      }
      if (object instanceof org.springframework.security.util.SimpleMethodInvocation) {
        SimpleMethodInvocation smi = (SimpleMethodInvocation) object;
        securedObject = smi.getMethod().getName();
      }
      span.setTag("secured.object", securedObject);
    }
  }

  public void setTagsFromAuth(AgentSpan span, Authentication auth) {
    assert span != null;
    assert auth != null;

    Object principal = auth.getPrincipal();

    if (principal instanceof UserDetails) {

      UserDetails ud = (UserDetails) principal;
      String username = ud.getUsername();
      if (username != null && !username.isEmpty()) {
        span.setTag("authentication.userdetails.username", username);
      }
      Boolean isAccountNonExpired = ud.isAccountNonExpired();
      span.setTag("authentication.userdetails.is_account_non_expired", isAccountNonExpired);
      Boolean isAccountNonLocked = ud.isAccountNonLocked();
      span.setTag("authentication.userdetails.is_account_non_locked", isAccountNonLocked);
      Boolean isCredentialsNonExpired = ud.isCredentialsNonExpired();
      span.setTag("authentication.userdetails.is_credentials_non_locked", isCredentialsNonExpired);

      Collection<? extends GrantedAuthority> coll = ud.getAuthorities();
      String str = new String();
      for (GrantedAuthority authority : coll) {
        str += DELIMITER;
        str += authority.getAuthority().toString();
      }
      if (coll.size() != 0) {
        span.setTag("authentication.userdetails.authorities", str.substring(DELIMITER.length()));
      }
    }

    span.setTag("authentication.name", auth.getName());

    boolean isAuthenticated = auth.isAuthenticated();
    span.setTag("authentication.is_authenticated", isAuthenticated);

  }
}
