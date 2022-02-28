package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.function.Consumer;
import java.io.IOException;

public interface PathwayContext {
  String INITIALIZATION_TOPIC = "";

  boolean isStarted();

  void setCheckpoint(String type, String group, String topic, Consumer<StatsPoint> pointConsumer);

  byte[] encode() throws IOException;
}
