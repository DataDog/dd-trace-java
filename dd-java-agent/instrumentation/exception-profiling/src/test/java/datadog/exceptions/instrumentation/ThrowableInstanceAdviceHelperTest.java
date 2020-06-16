package datadog.exceptions.instrumentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ThrowableInstanceAdviceHelperTest {
  @BeforeEach
  void setup() {
    ThrowableInstanceAdviceHelper.exitHandler(); // reset the tracker
  }

  @Test
  void singleReenter() {
    assertTrue(ThrowableInstanceAdviceHelper.enterHandler());
    assertFalse(ThrowableInstanceAdviceHelper.enterHandler());
  }

  @Test
  void reenterAfterExit() {
    assertTrue(ThrowableInstanceAdviceHelper.enterHandler());
    ThrowableInstanceAdviceHelper.exitHandler();
    assertTrue(ThrowableInstanceAdviceHelper.enterHandler());
  }

  @Test
  void reenterAfterExitMulti() {
    assertTrue(ThrowableInstanceAdviceHelper.enterHandler());
    for (int i = 0; i < 111; i++) {
      assertFalse(ThrowableInstanceAdviceHelper.enterHandler());
    }
    ThrowableInstanceAdviceHelper.exitHandler();
    assertTrue(ThrowableInstanceAdviceHelper.enterHandler());
  }

  @Test
  void multiExit() {
    assertTrue(ThrowableInstanceAdviceHelper.enterHandler());
    for (int i = 0; i < 111; i++) {
      ThrowableInstanceAdviceHelper.exitHandler();
    }
    assertTrue(ThrowableInstanceAdviceHelper.enterHandler());
  }
}
