package datadog.trace.api.datastreams;

import java.io.IOException;
import java.util.function.Consumer;

public interface PathwayContext {
  String PROPAGATION_KEY_BASE64 = "dd-pathway-ctx-base64";
  String DATADOG_KEY = "_datadog";

  boolean isStarted();

  long getHash();

  void setCheckpoint(DataStreamsContext context, Consumer<StatsPoint> pointConsumer);

  void saveStats(StatsPoint point);

  StatsPoint getSavedStats();

  String encode() throws IOException;
}
