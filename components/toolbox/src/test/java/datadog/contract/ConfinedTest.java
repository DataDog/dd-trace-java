package datadog.contract;

import static datadog.contract.Confined.Extent.CALL;
import static datadog.contract.Confined.Extent.FRAME;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import datadog.contract.Confined.Extent;
import org.junit.jupiter.api.Test;

class ConfinedTest {
  @Test
  void extentsAreDeclaredFromNarrowestToWidest() {
    // The Javadoc promises this order, so a new extent has to go in its place, not at the end.
    assertArrayEquals(new Extent[] {CALL, FRAME}, Extent.values());
  }
}
