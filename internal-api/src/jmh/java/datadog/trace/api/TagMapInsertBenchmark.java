package datadog.trace.api;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Inserting a span's worth of tags into a fresh {@link TagMap}, the way span decoration does: known
 * tags by Datadog name, the same known tags by id, and custom tags by name.
 *
 * <p>Single-threaded: each invocation builds its own map, so more threads would mostly measure
 * allocation bandwidth. Run with {@code -prof gc} for allocation per operation. Tag names are
 * string constants, as the tracer's are.
 *
 * <p>Results (MacBook, JDK 21, default flags, {@code @Fork(2)} triage), 12 tags per op:
 *
 * <pre>
 * Benchmark      ns/op         B/op
 * knownByName    121.1 +- 3.8  736
 * knownById       94.7 +- 1.7  736
 * customByName    80.6 +- 2.4  736
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@State(Scope.Benchmark)
public class TagMapInsertBenchmark {
  // Per-span tags a client or server decorator typically sets.
  static final String[] KNOWN_NAMES = {
    KnownTags.COMPONENT_NAME,
    KnownTags.SPAN_KIND_NAME,
    KnownTags.HTTP_METHOD_NAME,
    KnownTags.HTTP_URL_NAME,
    KnownTags.HTTP_ROUTE_NAME,
    KnownTags.HTTP_HOSTNAME_NAME,
    KnownTags.HTTP_USERAGENT_NAME,
    KnownTags.HTTP_CLIENT_IP_NAME,
    KnownTags.PEER_HOSTNAME_NAME,
    KnownTags.PEER_IPV4_NAME,
    KnownTags.PEER_PORT_NAME,
    KnownTags.DD_INTEGRATION_NAME,
  };

  static final long[] KNOWN_IDS = {
    KnownTags.COMPONENT_ID,
    KnownTags.SPAN_KIND_ID,
    KnownTags.HTTP_METHOD_ID,
    KnownTags.HTTP_URL_ID,
    KnownTags.HTTP_ROUTE_ID,
    KnownTags.HTTP_HOSTNAME_ID,
    KnownTags.HTTP_USERAGENT_ID,
    KnownTags.HTTP_CLIENT_IP_ID,
    KnownTags.PEER_HOSTNAME_ID,
    KnownTags.PEER_IPV4_ID,
    KnownTags.PEER_PORT_ID,
    KnownTags.DD_INTEGRATION_ID,
  };

  static final String[] CUSTOM_NAMES = new String[KNOWN_NAMES.length];

  static final Object[] VALUES = new Object[KNOWN_NAMES.length];

  static {
    for (int i = 0; i < KNOWN_NAMES.length; ++i) {
      CUSTOM_NAMES[i] = ("app.custom.tag." + i).intern();
      VALUES[i] = "value-" + i;
    }
  }

  @Benchmark
  public TagMap knownByName() {
    TagMap map = TagMap.create();
    for (int i = 0; i < KNOWN_NAMES.length; ++i) {
      map.set(KNOWN_NAMES[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public TagMap knownById() {
    TagMap map = TagMap.create();
    for (int i = 0; i < KNOWN_IDS.length; ++i) {
      map.set(KNOWN_IDS[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public TagMap customByName() {
    TagMap map = TagMap.create();
    for (int i = 0; i < CUSTOM_NAMES.length; ++i) {
      map.set(CUSTOM_NAMES[i], VALUES[i]);
    }
    return map;
  }
}
