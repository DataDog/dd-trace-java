package datadog.trace.instrumentation.springsecurity5;

import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION;
import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.api.EventTracker;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.appsec.AppSecEventTracker;
import datadog.trace.api.telemetry.LoginEvent;
import datadog.trace.api.telemetry.LoginFramework;
import datadog.trace.api.telemetry.WafMetricCollector;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

public class SpringSecurityUserEventDecorator {

  public static final SpringSecurityUserEventDecorator DECORATE =
      new SpringSecurityUserEventDecorator();
  private static final String SPRING_SECURITY_PACKAGE = "org.springframework.security";

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SpringSecurityUserEventDecorator.class);

  private static final Set<Class<?>> SKIPPED_AUTHS =
      Collections.newSetFromMap(new ConcurrentHashMap<>());

  public void onUserNotFound() {
    final AppSecEventTracker tracker = getEventTracker();
    if (tracker == null) {
      return;
    }
    tracker.onUserNotFound(UserIdCollectionMode.get());
  }

  public void onSignup(UserDetails user, Throwable throwable) {
    // skip failures while signing up a user, later on, we might want to generate a separate event
    // for this case
    if (throwable != null) {
      return;
    }

    final AppSecEventTracker tracker = getEventTracker();
    if (tracker == null) {
      return;
    }

    UserIdCollectionMode mode = UserIdCollectionMode.get();
    String username = user.getUsername();
    if (missingUsername(username, LoginEvent.SIGN_UP)) {
      return;
    }
    Map<String, String> metadata = null;
    if (mode == IDENTIFICATION) {
      metadata = new HashMap<>();
      metadata.put("enabled", String.valueOf(user.isEnabled()));
      metadata.put(
          "authorities",
          user.getAuthorities().stream().map(Object::toString).collect(Collectors.joining(",")));
    }

    tracker.onSignupEvent(mode, username, metadata);
  }

  public void onLogin(Authentication authentication, Throwable throwable, Authentication result) {
    if (authentication == null) {
      return;
    }

    final AppSecEventTracker tracker = getEventTracker();
    if (tracker == null) {
      return;
    }

    if (shouldSkipAuthentication(authentication)) {
      return;
    }

    UserIdCollectionMode mode = UserIdCollectionMode.get();
    String username = result != null ? result.getName() : authentication.getName();
    if (throwable == null && result != null && result.isAuthenticated()) {
      if (missingUsername(username, LoginEvent.LOGIN_SUCCESS)) {
        return;
      }
      Map<String, String> metadata = null;
      Object principal = result.getPrincipal();
      if (principal instanceof User) {
        User user = (User) principal;
        metadata = new HashMap<>();
        metadata.put("enabled", String.valueOf(user.isEnabled()));
        metadata.put(
            "authorities",
            user.getAuthorities().stream().map(Object::toString).collect(Collectors.joining(",")));
        metadata.put("accountNonExpired", String.valueOf(user.isAccountNonExpired()));
        metadata.put("accountNonLocked", String.valueOf(user.isAccountNonLocked()));
        metadata.put("credentialsNonExpired", String.valueOf(user.isCredentialsNonExpired()));
      }
      tracker.onLoginSuccessEvent(mode, username, metadata);
    } else if (throwable != null) {
      if (missingUsername(username, LoginEvent.LOGIN_FAILURE)) {
        return;
      }
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
      tracker.onLoginFailureEvent(mode, username, null, null);
    }
  }

  public void onUser(final Authentication authentication) {
    if (authentication == null) {
      return;
    }

    final AppSecEventTracker tracker = getEventTracker();
    if (tracker == null) {
      return;
    }

    if (shouldSkipAuthentication(authentication)) {
      return;
    }

    UserIdCollectionMode mode = UserIdCollectionMode.get();
    String username = authentication.getName();
    if (missingUserId(username)) {
      return;
    }
    tracker.onUserEvent(mode, username);
  }

  private static boolean shouldSkipAuthentication(final Authentication authentication) {
    if (authentication instanceof UsernamePasswordAuthenticationToken) {
      return false;
    }
    if (SKIPPED_AUTHS.add(authentication.getClass())) {
      final Class<?> authClass = authentication.getClass();
      LOGGER.debug(
          SEND_TELEMETRY, "Skipped authentication, auth={}", findRootAuthentication(authClass));
    }
    return true;
  }

  private static String findRootAuthentication(Class<?> authentication) {
    while (authentication != Object.class) {
      if (authentication.getName().startsWith(SPRING_SECURITY_PACKAGE)) {
        return authentication.getName();
      }
      authentication = authentication.getSuperclass();
    }
    return Authentication.class.getName(); // set this a default for really custom impls
  }

  private static boolean missingUserId(final String username) {
    if (username == null || username.isEmpty()) {
      WafMetricCollector.get().missingUserId(LoginFramework.SPRING_SECURITY);
      return true;
    }
    return false;
  }

  private static boolean missingUsername(final String username, final LoginEvent event) {
    if (username == null || username.isEmpty()) {
      WafMetricCollector.get().missingUserLogin(LoginFramework.SPRING_SECURITY, event);
      return true;
    }
    return false;
  }

  private static AppSecEventTracker getEventTracker() {
    final EventTracker tracker = GlobalTracer.getEventTracker();
    return tracker instanceof AppSecEventTracker ? (AppSecEventTracker) tracker : null;
  }
}
