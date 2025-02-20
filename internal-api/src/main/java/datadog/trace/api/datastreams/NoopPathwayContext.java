package datadog.trace.api.datastreams;

import java.util.function.Consumer;

public class NoopPathwayContext implements PathwayContext {
  public static final NoopPathwayContext INSTANCE = new NoopPathwayContext();

  @Override
  public boolean isStarted() {
    return false;
  }

  @Override
  public long getHash() {
    return 0L;
  }

  @Override
  public void setCheckpoint(DataStreamsContext context, Consumer<StatsPoint> pointConsumer) {}

  @Override
  public void saveStats(StatsPoint point) {}

  @Override
  public StatsPoint getSavedStats() {
    return null;
  }

  @Override
  public String encode() {
    return null;
  }
}
