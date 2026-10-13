package datadog.metrics.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecordingTest {

  @Test
  void closeDelegatesToStop() {
    StoppableRecording recording = new StoppableRecording();

    recording.close();

    assertTrue(recording.stopped);
  }

  private static final class StoppableRecording extends Recording {
    boolean stopped;

    @Override
    public Recording start() {
      return this;
    }

    @Override
    public void reset() {}

    @Override
    public void stop() {
      stopped = true;
    }

    @Override
    public void flush() {}
  }
}
