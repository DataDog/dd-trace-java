package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Covers the AppSec runtime-activation callbacks, the only signal other subsystems get for the
 * remote-config "one-click" activation flow, where the boot-time activation level never changes.
 */
class ActiveSubsystemsTest {

  private boolean originalAppSecActive;

  @BeforeEach
  void saveState() {
    originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = false;
  }

  @AfterEach
  void restoreState() {
    ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
  }

  @Test
  void callbackRunsWhenAppSecBecomesActive() {
    AtomicInteger runs = new AtomicInteger();

    ActiveSubsystems.whenAppSecActivated(runs::incrementAndGet);
    assertEquals(0, runs.get());

    ActiveSubsystems.setAppSecActive(true);

    assertTrue(ActiveSubsystems.APPSEC_ACTIVE);
    assertEquals(1, runs.get());
  }

  @Test
  void callbackRunsAtMostOnceAcrossRepeatedToggles() {
    AtomicInteger runs = new AtomicInteger();
    ActiveSubsystems.whenAppSecActivated(runs::incrementAndGet);

    ActiveSubsystems.setAppSecActive(true);
    ActiveSubsystems.setAppSecActive(false);
    ActiveSubsystems.setAppSecActive(true);

    assertEquals(1, runs.get());
  }

  @Test
  void callbackRunsImmediatelyWhenAppSecIsAlreadyActive() {
    AtomicInteger runs = new AtomicInteger();
    ActiveSubsystems.setAppSecActive(true);

    ActiveSubsystems.whenAppSecActivated(runs::incrementAndGet);

    assertEquals(1, runs.get());
  }

  @Test
  void callbackNeverRunsWhileAppSecStaysInactive() {
    AtomicInteger runs = new AtomicInteger();

    ActiveSubsystems.whenAppSecActivated(runs::incrementAndGet);
    ActiveSubsystems.setAppSecActive(false);

    assertFalse(ActiveSubsystems.APPSEC_ACTIVE);
    assertEquals(0, runs.get());
  }

  @Test
  void aFailingCallbackNeitherBreaksActivationNorTheOtherCallbacks() {
    AtomicInteger runs = new AtomicInteger();
    ActiveSubsystems.whenAppSecActivated(
        () -> {
          throw new IllegalStateException("boom");
        });
    ActiveSubsystems.whenAppSecActivated(runs::incrementAndGet);

    ActiveSubsystems.setAppSecActive(true);

    assertTrue(ActiveSubsystems.APPSEC_ACTIVE);
    assertEquals(1, runs.get());
  }
}
