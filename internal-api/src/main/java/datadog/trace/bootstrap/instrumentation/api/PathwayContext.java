package datadog.trace.bootstrap.instrumentation.api;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

public interface PathwayContext {
  String PROPAGATION_KEY = "dd-pathway-ctx";
  String PROPAGATION_KEY_BASE64 = "dd-pathway-ctx-base64";

  boolean isStarted();

  long getHash();

  // The input tags should be sorted.
  PathwayContext createNew(
      LinkedHashMap<String, String> sortedTags, Consumer<StatsPoint> pointConsumer);

  byte[] encode() throws IOException;

  String strEncode() throws IOException;
}
