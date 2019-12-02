package datadog.trace.instrumentation.springsecurity;

import datadog.trace.agent.decorator.BaseDecorator;
import datadog.trace.api.DDTags;
import datadog.trace.instrumentation.api.AgentSpan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.security.web.FilterInvocation;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import java.util.Collection;

@Slf4j
public class SpringSecurityDecorator extends BaseDecorator {
  public static final String DELIMITER = ", ";
  public static final SpringSecurityDecorator DECORATOR = new SpringSecurityDecorator();

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
    return null;
  }

  public AgentSpan afterStart(final AgentSpan span) {
    assert span != null;
    span.setTag(DDTags.SERVICE_NAME, service());
    return super.afterStart(span);
  }

  public AgentSpan setTagsFromFilterInvocation(
      AgentSpan span, org.springframework.security.web.FilterInvocation fi) {
    span.setTag("request.fullURL", fi.getFullRequestUrl());
    span.setTag("request.URL", fi.getRequestUrl());
    return span;
  }

  public AgentSpan setTagsFromMethodInvocation(
      AgentSpan span, org.springframework.security.util.SimpleMethodInvocation smi) {
    span.setTag("request.method", smi.getMethod().getName());
    // TO DO
    // add tags arguments
    return span;
  }

  public AgentSpan setTagsFromConfigAttributes(
      AgentSpan span, Collection<ConfigAttribute> configAttributes) {
    String str = new String();
    for (ConfigAttribute ca : configAttributes) {
      str += DELIMITER;
      str += ca.getAttribute();
    }
    if (configAttributes.size() != 0) {
      span.setTag("config.attribute", str.substring(DELIMITER.length()));
    }
    return span;
  }

  public AgentSpan setTagsFromSecuredObject(AgentSpan span, Object object) {
    if (object != null && (object instanceof org.springframework.security.web.FilterInvocation)) {
      FilterInvocation fi = (FilterInvocation) object;
      setTagsFromFilterInvocation(span, fi);
    }

    if (object != null
        && (object instanceof org.springframework.security.util.SimpleMethodInvocation)) {
      SimpleMethodInvocation smi = (SimpleMethodInvocation) object;
      setTagsFromMethodInvocation(span, smi);
    }
    return span;
  }

  public AgentSpan setTagsFromAuth(AgentSpan span, Authentication auth) {
    assert span != null;
    assert auth != null;

    Object principal = auth.getPrincipal();
    if (principal != null) {

      if (principal instanceof UserDetails) {

        UserDetails ud = (UserDetails) principal;
        String username = ud.getUsername();
        if (username != null && !username.isEmpty()) {
          span.setTag("authentication.principal.username", username);
        }
        Boolean isAccountNonExpired = ud.isAccountNonExpired();
        span.setTag("authentication.principal.is_account_non_expired", isAccountNonExpired);
        Boolean isAccountNonLocked = ud.isAccountNonLocked();
        span.setTag("authentication.principal.is_account_non_locked", isAccountNonLocked);
        Boolean isCredentialsNonExpired = ud.isCredentialsNonExpired();
        span.setTag("authentication.principal.is_credentials_non_locked", isCredentialsNonExpired);

        Collection<? extends GrantedAuthority> coll = ud.getAuthorities();
        String str = new String();
        for (GrantedAuthority authority : coll) {
          str += DELIMITER;
          str += authority.getAuthority().toString();
        }
        if (coll.size() != 0) {
          span.setTag("authentication.authority", str.substring(DELIMITER.length()));
        }
      } else {
        System.out.println("authentication.principal");
        span.setTag("authentication.principal", principal.toString());
      }
    }

    boolean isAuthenticated = auth.isAuthenticated();
    span.setTag("authentication.isAuthenticated", isAuthenticated);

    Object details = auth.getDetails();
    if (details != null && details instanceof WebAuthenticationDetails) {
      WebAuthenticationDetails wad = (WebAuthenticationDetails) details;

      String sessionID = wad.getSessionId();
      if (sessionID != null && !sessionID.isEmpty()) {
        span.setTag("authentication.web.details.sessionID", sessionID);
      }

      String remoteAddress = wad.getRemoteAddress();
      if (remoteAddress != null && !remoteAddress.isEmpty()) {
        span.setTag("authentication.web.details.remoteAddress", remoteAddress);
      }
    }

    return span;
  }
}
