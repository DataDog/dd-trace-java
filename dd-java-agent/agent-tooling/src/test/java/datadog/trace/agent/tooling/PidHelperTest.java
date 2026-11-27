package datadog.trace.agent.tooling;

import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.PidHelper;
import org.junit.jupiter.api.Test;

class PidHelperTest extends DDJavaSpecification {

  @Test
  void pidIsAvailableEverywhereWeTest() {
    assertFalse(PidHelper.getPid().isEmpty());
  }
}
