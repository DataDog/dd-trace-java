package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.api.UserEventTrackingMode.DISABLED;
import static datadog.trace.api.UserEventTrackingMode.EXTENDED;

import datadog.trace.api.Config;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.bootstrap.instrumentation.decorator.AppSecUserEventDecorator;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;

public class SpringSecurityUserEventDecorator extends AppSecUserEventDecorator {

  public static final SpringSecurityUserEventDecorator DECORATE =
      new SpringSecurityUserEventDecorator();

  public void onSignup(UserDetails user) {
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    String userId = null;

    if (mode == EXTENDED && user != null) {
      userId = user.getUsername();
    }

    if (mode != DISABLED) {
      onSignup(userId, null);
    }
  }

  public void onLogin(Authentication authentication, Throwable throwable, Authentication result) {
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();

    String userId = null;
    if (mode == EXTENDED && authentication != null) {
      userId = authentication.getName();
    }

    if (mode != DISABLED) {
      if (throwable == null && result != null && result.isAuthenticated()) {
        onLoginSuccess(userId, null);
      } else {
        boolean userExists = throwable instanceof BadCredentialsException;
        onLoginFailure(userId, null, userExists);
      }
    }
  }
}
