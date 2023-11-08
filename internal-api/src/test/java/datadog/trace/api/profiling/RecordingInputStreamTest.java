package datadog.trace.api.profiling;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

class RecordingInputStreamTest {

  @Test
  void isEmpty() throws Exception {
    RecordingInputStream instance = new RecordingInputStream(new ByteArrayInputStream(new byte[0]));
    assertTrue(instance.isEmpty());
  }

  @Test
  void isNotEmpty() throws Exception {
    RecordingInputStream instance =
        new RecordingInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    assertFalse(instance.isEmpty());
    instance.read(new byte[3], 0, 3); // exhaust the stream
    assertFalse(instance.isEmpty()); // still should not report as empty
  }
}
