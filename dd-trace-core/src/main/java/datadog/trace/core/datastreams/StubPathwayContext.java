package datadog.trace.core.datastreams;

import datadog.trace.api.function.Consumer;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import java.io.IOException;

public class StubPathwayContext implements PathwayContext {
  public static final StubPathwayContext INSTANCE = new StubPathwayContext();

  @Override
  public boolean isStarted() {
    return false;
  }

  @Override
  public void setCheckpoint(
      String type, String group, String topic, Consumer<StatsPoint> pointConsumer) {}

  @Override
  public byte[] encode() throws IOException {
    return new byte[0];
  }
}
