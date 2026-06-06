package datadog.trace.instrumentation.springsecurity6;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.instrumentation.springsecurity5.SpringSecurityUserEventDecorator;
import datadog.trace.instrumentation.springsecurity5.UsernameNotFoundExceptionFactoryInstrumentation;
import org.junit.jupiter.api.Test;

/**
 * Unit-level checks for the Spring Security 7 {@code UsernameNotFoundException.fromUsername}
 * factory hook.
 *
 * <p>The end-to-end AppSec event assertion lives in the existing {@code SpringBootBasedTest}
 * Groovy spec; when {@code latestDepTest} resolves Spring Boot 4 (Spring Security 7) the
 * "test failed login with non existing user" case exercises the {@code fromUsername} factory
 * path through {@code InMemoryUserDetailsManager} and verifies the AppSec login-failure tags
 * are emitted. This Java test pins the contract pieces that aren't covered there: the new
 * instrumentation targets the exact {@code UsernameNotFoundException} type, and the decorator
 * overload that the advice invokes is callable without throwing.
 */
class UsernameNotFoundFromUsernameTest {

  @Test
  void instrumentationTargetsUsernameNotFoundException() {
    final UsernameNotFoundExceptionFactoryInstrumentation instrumentation =
        new UsernameNotFoundExceptionFactoryInstrumentation();
    assertEquals(
        "org.springframework.security.core.userdetails.UsernameNotFoundException",
        instrumentation.instrumentedType());
  }

  @Test
  void decoratorAcceptsUsernameOverload() {
    // The advice forwards Argument(0) into this overload; ensure it is callable in isolation
    // (no AppSec tracker is registered in this unit test, so the decorator must no-op cleanly).
    assertDoesNotThrow(() -> SpringSecurityUserEventDecorator.DECORATE.onUserNotFound("alice"));
    assertDoesNotThrow(() -> SpringSecurityUserEventDecorator.DECORATE.onUserNotFound(null));
  }
}
