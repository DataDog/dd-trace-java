package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.api.UserEventTrackingMode.DISABLED;
import static datadog.trace.api.UserEventTrackingMode.EXTENDED;

import datadog.trace.api.Config;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.bootstrap.instrumentation.decorator.AppSecUserEventDecorator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

public class SpringSecurityUserEventDecorator extends AppSecUserEventDecorator {

  public static final SpringSecurityUserEventDecorator DECORATE =
      new SpringSecurityUserEventDecorator();

  public void onSignup(UserDetails user, Throwable throwable) {
    // skip failures while signing up a user, later on, we might want to generate a separate event
    // for this case
    if (throwable != null) {
      return;
    }

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
    if (authentication == null) {
      return;
    }
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode == DISABLED) {
      return;
    }

    // For now, exclude all OAuth events. See APPSEC-12547.
    if (authentication.getClass().getName().contains("OAuth")) {
      return;
    }

    String userId = null;

    if (mode == EXTENDED) {
      userId = authentication.getName();
    }

    if (mode != DISABLED) {
      if (throwable == null && result != null && result.isAuthenticated()) {
        Map<String, String> metadata = null;
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
      } else if (throwable != null) {
        // On bad password, throwable would be
        // org.springframework.security.authentication.BadCredentialsException,
        // on user not found, throwable can be BadCredentials or
        // org.springframework.security.core.userdetails.UsernameNotFoundException depending on the
        // internal setting
        // hideUserNotFoundExceptions (or a custom AuthenticationProvider implementation overriding
        // this).
        // This would be the ideal place to check whether the user exists or not, but we cannot do
        // so reliably yet.
        // See UsernameNotFoundExceptionInstrumentation for more details.
        onLoginFailure(userId, null);
      }
    }
  }
}
