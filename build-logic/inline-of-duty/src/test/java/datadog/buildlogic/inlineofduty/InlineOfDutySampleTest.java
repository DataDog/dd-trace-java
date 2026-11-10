package datadog.buildlogic.inlineofduty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

class InlineOfDutySampleTest {
  @Test
  void selectsAboutOneTenthOfTasksReproduciblyAndRotatesAcrossPipelines() {
    long first =
        IntStream.range(0, 1000)
            .filter(i -> InlineOfDutySample.selected(true, 10, ":module" + i + ":test", "123"))
            .count();
    long repeated =
        IntStream.range(0, 1000)
            .filter(i -> InlineOfDutySample.selected(true, 10, ":module" + i + ":test", "123"))
            .count();
    long changed =
        IntStream.range(0, 1000)
            .filter(
                i ->
                    InlineOfDutySample.selected(true, 10, ":module" + i + ":test", "123")
                        != InlineOfDutySample.selected(true, 10, ":module" + i + ":test", "124"))
            .count();
    assertEquals(first, repeated);
    assertTrue(first >= 70 && first <= 130, "A broad task population should be near 10%");
    assertTrue(changed > 100, "Pipeline identity should rotate sampled tasks");
  }

  @Test
  void zeroAndFullRatesAndInvalidValuesAreExplicit() {
    assertFalse(InlineOfDutySample.selected(true, 0, ":test", "123"));
    assertFalse(InlineOfDutySample.selected(false, 100, ":test", "123"));
    assertTrue(InlineOfDutySample.selected(true, 100, ":test", "123"));
    assertThrows(
        GradleException.class, () -> InlineOfDutySample.selected(true, -1, ":test", "123"));
    assertThrows(
        GradleException.class, () -> InlineOfDutySample.selected(true, 101, ":test", "123"));
  }
}
