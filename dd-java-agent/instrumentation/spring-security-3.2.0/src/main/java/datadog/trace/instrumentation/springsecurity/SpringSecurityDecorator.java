package datadog.trace.instrumentation.springsecurity;

import datadog.trace.agent.decorator.BaseDecorator;
import datadog.trace.api.DDTags;
import datadog.trace.instrumentation.api.AgentSpan;
import java.util.Collection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

@Slf4j
public class SpringSecurityDecorator extends BaseDecorator {
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

        // TESTED
        Collection<? extends GrantedAuthority> coll = ud.getAuthorities();
        int i = 0;
        for (GrantedAuthority authority : coll) {
          span.setTag("authentication.authority." + i, authority.getAuthority());
          i++;
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
