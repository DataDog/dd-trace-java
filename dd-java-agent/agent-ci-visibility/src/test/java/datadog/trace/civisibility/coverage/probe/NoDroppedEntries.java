package datadog.trace.civisibility.coverage.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Fails a test in which a method started inside the recorder's own work, such as a move of hits,
 * whose hits the recorder drops: no code of an exact test runs inside that work.
 */
final class NoDroppedEntries implements BeforeEachCallback, AfterEachCallback {

  private long droppedBefore;

  @Override
  public void beforeEach(ExtensionContext context) {
    droppedBefore = ProbeRecorder.getDroppedEntryCount();
  }

  @Override
  public void afterEach(ExtensionContext context) {
    assertEquals(
        droppedBefore,
        ProbeRecorder.getDroppedEntryCount(),
        "methods started inside the recorder's own work");
  }
}
