package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class DebuggerFeaturesTest {

  @Test
  public void roundtripSerialization() throws Exception {
    String buffer = serialize();
    System.out.println(buffer);
    deserialize(buffer);
  }

  private String serialize() throws IOException {
    DebuggerFeatures features = new DebuggerFeatures();
    features.enabled = true;
    return DebuggerFeaturesDeserializer.INSTANCE.serialize(features);
  }

  private void deserialize(String buffer) throws IOException {
    DebuggerFeatures features =
        DebuggerFeaturesDeserializer.INSTANCE.deserialize(buffer.getBytes(StandardCharsets.UTF_8));
    assertEquals(true, features.enabled);
  }
}
