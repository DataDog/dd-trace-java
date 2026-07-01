package datadog.trace.bootstrap.instrumentation.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KeyClassifierTest {

  static class RecordingClassifier implements AgentPropagation.KeyClassifier {
    String lastKey;
    String lastValue;
    boolean returnValue;

    RecordingClassifier(boolean returnValue) {
      this.returnValue = returnValue;
    }

    @Override
    public boolean accept(String key, String value) {
      lastKey = key;
      lastValue = value;
      return returnValue;
    }
  }

  @Test
  void defaultTransformerMethodAppliesTransformerAndDelegates() {
    RecordingClassifier classifier = new RecordingClassifier(true);

    boolean result =
        classifier.accept(
            "my-key",
            "raw".getBytes(StandardCharsets.UTF_8),
            bytes -> new String(bytes, StandardCharsets.UTF_8));

    assertEquals("my-key", classifier.lastKey);
    assertEquals("raw", classifier.lastValue);
    assertTrue(result);
  }

  @Test
  void transformerIsCalledExactlyOnce() {
    AtomicInteger callCount = new AtomicInteger(0);
    AtomicReference<String> transformed = new AtomicReference<>();

    AgentPropagation.KeyClassifier classifier =
        (key, value) -> {
          transformed.set(value);
          return true;
        };

    classifier.accept(
        "key",
        "input",
        v -> {
          callCount.incrementAndGet();
          return v.toUpperCase();
        });

    assertEquals(1, callCount.get());
    assertEquals("INPUT", transformed.get());
  }

  @Test
  void existingAcceptStringStringContractUnchanged() {
    RecordingClassifier classifier = new RecordingClassifier(true);

    boolean result = classifier.accept("trace-id", "abc123");

    assertEquals("trace-id", classifier.lastKey);
    assertEquals("abc123", classifier.lastValue);
    assertTrue(result);
  }
}
