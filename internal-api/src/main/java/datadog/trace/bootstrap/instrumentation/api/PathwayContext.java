package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.function.Consumer;
import java.io.IOException;
import java.util.LinkedHashMap;

public interface PathwayContext {
  String PROPAGATION_KEY = "dd-pathway-ctx";
  String PROPAGATION_KEY_BASE64 = "dd-pathway-ctx-base64";

  boolean isStarted();

  // The input tags should be sorted.
  void setCheckpoint(LinkedHashMap<String, String> sortedTags, Consumer<StatsPoint> pointConsumer);

  byte[] encode() throws IOException;

  String strEncode() throws IOException;
}
