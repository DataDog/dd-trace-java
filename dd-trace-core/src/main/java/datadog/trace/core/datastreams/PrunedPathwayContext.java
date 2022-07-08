package datadog.trace.core.datastreams;

import com.datadoghq.sketch.ddsketch.encoding.ByteArrayInput;
import com.datadoghq.sketch.ddsketch.encoding.GrowingByteArrayOutput;
import com.datadoghq.sketch.ddsketch.encoding.VarEncodingHelper;
import datadog.trace.api.Config;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.function.Consumer;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.PathwayContext;
import datadog.trace.bootstrap.instrumentation.api.StatsPoint;
import datadog.trace.util.FNV64Hash;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PrunedPathwayContext implements PathwayContext {
  private static final Logger log = LoggerFactory.getLogger(PrunedPathwayContext.class);

  public static final PrunedPathwayContext INSTANCE = new PrunedPathwayContext();

  @Override
  public boolean isStarted() {
    return false;
  }

  @Override
  public boolean isPruned() { return true; }

  @Override
  public void start(Consumer<StatsPoint> pointConsumer) { }

  @Override
  public void setCheckpoint(
      String type, String group, String topic, Consumer<StatsPoint> pointConsumer) { }

  @Override
  public byte[] encode() throws IOException {
    return null;
  }

  @Override
  public String toString() {
    return "PathwayContext [Pruned]";
  }

  private static class PathwayContextExtractor implements AgentPropagation.BinaryKeyClassifier {
    private PrunedPathwayContext extractedContext;

    @Override
    public boolean accept(String key, byte[] value) {
      if (PathwayContext.PROPAGATION_KEY_PRUNED.equalsIgnoreCase(key)) {
        extractedContext = PrunedPathwayContext.INSTANCE;
      }
      return true;
    }
  }

  public static <C> PrunedPathwayContext extract(
      C carrier,
      AgentPropagation.BinaryContextVisitor<C> getter) {
    PrunedPathwayContext.PathwayContextExtractor pathwayContextExtractor =
        new PrunedPathwayContext.PathwayContextExtractor();
    getter.forEachKey(carrier, pathwayContextExtractor);

    if (pathwayContextExtractor.extractedContext != null) {
      log.debug("Extracted pruned context");
    }

    return pathwayContextExtractor.extractedContext;
  }
/*
  // TODO Can be removed when Java7 support is removed
  private static String toUnsignedString(long l) {
    if (l >= 0) {
      return Long.toString(l);
    }

    // shift left once and divide by 5 results in an unsigned divide by 10
    long quot = (l >>> 1) / 5;
    long rem = l - quot * 10;
    return Long.toString(quot) + rem;
  }

  private static class PathwayContextExtractor implements AgentPropagation.BinaryKeyClassifier {
    private final TimeSource timeSource;
    private final WellKnownTags wellKnownTags;
    private DefaultPathwayContext extractedContext;

    PathwayContextExtractor(TimeSource timeSource, WellKnownTags wellKnownTags) {
      this.timeSource = timeSource;
      this.wellKnownTags = wellKnownTags;
    }

    @Override
    public boolean accept(String key, byte[] value) {
      if (PathwayContext.PROPAGATION_KEY.equalsIgnoreCase(key)) {
        try {
          extractedContext = decode(timeSource, wellKnownTags, value);
        } catch (IOException e) {
          return false;
        }
      }
      return true;
    }
  }

  public static <C> DefaultPathwayContext extract(
      C carrier,
      AgentPropagation.BinaryContextVisitor<C> getter,
      TimeSource timeSource,
      WellKnownTags wellKnownTags) {
    PathwayContextExtractor pathwayContextExtractor =
        new PathwayContextExtractor(timeSource, wellKnownTags);
    getter.forEachKey(carrier, pathwayContextExtractor);

    if (pathwayContextExtractor.extractedContext == null) {
      log.debug("No context extracted");
    } else {
      log.debug("Extracted context: {} ", pathwayContextExtractor.extractedContext);
    }

    return pathwayContextExtractor.extractedContext;
  }

  public static DefaultPathwayContext decode(
      TimeSource timeSource, WellKnownTags wellKnownTags, byte[] data) throws IOException {
    ByteArrayInput input = ByteArrayInput.wrap(data);

    long hash = input.readLongLE();

    long pathwayStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long pathwayStartNanos = TimeUnit.MILLISECONDS.toNanos(pathwayStartMillis);

    // Convert the start time to the current JVM's nanoclock
    long nowNanos = timeSource.getCurrentTimeNanos();
    long nanosSinceStart = nowNanos - pathwayStartNanos;
    long nowNanoTicks = timeSource.getNanoTicks();
    long pathwayStartNanoTicks = nowNanoTicks - nanosSinceStart;

    long edgeStartMillis = VarEncodingHelper.decodeSignedVarLong(input);
    long edgeStartNanoTicks =
        pathwayStartNanoTicks + TimeUnit.MILLISECONDS.toNanos(edgeStartMillis - pathwayStartMillis);

    return new DefaultPathwayContext(
        timeSource,
        wellKnownTags,
        pathwayStartNanos,
        pathwayStartNanoTicks,
        edgeStartNanoTicks,
        hash);
  }*/
}
