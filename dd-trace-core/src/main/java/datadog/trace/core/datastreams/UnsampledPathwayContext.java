package datadog.trace.core.datastreams;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.api.datastreams.PathwayContext;
import datadog.trace.api.datastreams.StatsPoint;
import java.util.function.Consumer;

/**
 * A message that is on a pathway whose origin was not sampled, so nothing downstream records it.
 *
 * <p>It is started, so child spans inherit it instead of starting a pathway of their own. It never
 * checkpoints, and every produce under it forwards it.
 *
 * <p>On the wire it is {@link #ENCODED} in {@code dd-pathway-ctx-base64}: an all-zero hash with no
 * timestamps after it. Every Data Streams decoder, in Java and the other tracers, current and older
 * versions, rejects it as truncated and carries on as if there were no header, which starts a new
 * pathway as it does today. A complete value with hash 0 would be accepted with latencies measured
 * from the epoch, and a value that isn't base64 throws out of extraction in some of them. Using the
 * existing header also means producers that re-inject replace it rather than forwarding it stale.
 */
public final class UnsampledPathwayContext implements PathwayContext {
  public static final UnsampledPathwayContext INSTANCE = new UnsampledPathwayContext();

  static final String ENCODED = "AAAAAAAAAAA=";
  private static final byte[] ENCODED_BYTES = ENCODED.getBytes(ISO_8859_1);

  private UnsampledPathwayContext() {}

  @Override
  public boolean isStarted() {
    return true;
  }

  @Override
  public long getHash() {
    return 0;
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
    return ENCODED;
  }

  /** A copy, because carriers such as Kafka headers keep the array and hand it to user code. */
  @Override
  public byte[] encodeBytes() {
    return ENCODED_BYTES.clone();
  }

  @Override
  public String toString() {
    return "PathwayContext [Unsampled]";
  }
}
