package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.api.UserEventTrackingMode.DISABLED;
import static datadog.trace.api.UserEventTrackingMode.EXTENDED;

import datadog.trace.api.Config;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.bootstrap.instrumentation.decorator.AppSecUserEventDecorator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

public class SpringSecurityUserEventDecorator extends AppSecUserEventDecorator {

  public static final SpringSecurityUserEventDecorator DECORATE =
      new SpringSecurityUserEventDecorator();

  public void onSignup(UserDetails user) {
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    String userId = null;
    Map<String, String> metadata = null;

    if (mode == EXTENDED && user != null) {
      userId = user.getUsername();

      metadata = new HashMap<>();
      metadata.put("enabled", String.valueOf(user.isEnabled()));
      metadata.put(
          "authorities",
          user.getAuthorities().stream().map(Object::toString).collect(Collectors.joining(",")));
    }

    if (mode != DISABLED) {
      onSignup(userId, metadata);
    }
  }

  public void onLogin(Authentication authentication, Throwable throwable, Authentication result) {
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();

    String userId = null;
    Map<String, String> metadata = null;

    if (mode == EXTENDED && authentication != null) {
      userId = authentication.getName();
    }

    if (mode != DISABLED) {
      if (throwable == null && result != null && result.isAuthenticated()) {

        Object principal = result.getPrincipal();
        if (principal instanceof User) {
          User user = (User) principal;
          metadata = new HashMap<>();
          metadata.put("enabled", String.valueOf(user.isEnabled()));
          metadata.put(
              "authorities",
              user.getAuthorities().stream()
                  .map(Object::toString)
                  .collect(Collectors.joining(",")));
          metadata.put("accountNonExpired", String.valueOf(user.isAccountNonExpired()));
          metadata.put("accountNonLocked", String.valueOf(user.isAccountNonLocked()));
          metadata.put("credentialsNonExpired", String.valueOf(user.isCredentialsNonExpired()));
        }

        onLoginSuccess(userId, metadata);
      } else {
        boolean userExists = throwable instanceof BadCredentialsException;
        onLoginFailure(userId, null, userExists);
      }
    }
  }
}
