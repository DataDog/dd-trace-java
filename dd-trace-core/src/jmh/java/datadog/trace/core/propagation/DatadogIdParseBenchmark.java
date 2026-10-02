package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import datadog.trace.api.Config;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.LoggerFactory;

/**
 * Cost of parsing an {@code x-datadog-trace-id} header value, valid and invalid, with the throwing
 * {@link DD64bTraceId#from(String)} and the non-throwing {@link DD64bTraceId#fromOrNull(String)},
 * and of a full Datadog-style extraction carrying that value.
 *
 * <p>The invalid values are the two shapes behind the highest-volume propagation entries in Error
 * Tracking: a value past the unsigned 64 bit range ({@code numberFormatOutOfLongRange}) and a value
 * that is not decimal at all. Valid values cover both the 18 digit path and the 19 digit path,
 * where most randomly generated 63 bit ids fall.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(2)
public class DatadogIdParseBenchmark {
  @Param({
    "123456789012345678", // valid, 18 digits
    "8217536438929873290", // valid, 19 digits
    "18446744073709551616", // invalid, unsigned max + 1
    "1a2b3c4d5e6f" // invalid, not decimal
  })
  String traceIdHeader;

  HttpCodec.Extractor extractor;
  Map<String, String> headers;

  @Setup
  public void setUp() {
    // The test logback.xml on the JMH classpath sets the root logger to DEBUG. Production runs at
    // INFO by default, and per-request debug output would swamp the invalid-header arms.
    ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);

    DynamicConfig dynamicConfig =
        DynamicConfig.create()
            .setHeaderTags(Collections.emptyMap())
            .setBaggageMapping(Collections.emptyMap())
            .apply();
    extractor = DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    headers = new LinkedHashMap<>();
    headers.put(DatadogHttpCodec.TRACE_ID_KEY, traceIdHeader);
    headers.put(DatadogHttpCodec.SPAN_ID_KEY, "2345678901234567890");
    headers.put(DatadogHttpCodec.SAMPLING_PRIORITY_KEY, "1");
    headers.put("user-agent", "benchmark");
  }

  @Benchmark
  public DDTraceId fromThrowing() {
    try {
      return DD64bTraceId.from(traceIdHeader);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  @Benchmark
  public DDTraceId fromOrNull() {
    return DD64bTraceId.fromOrNull(traceIdHeader);
  }

  @Benchmark
  public TagContext extract() {
    return extractor.extract(headers, MAP_VISITOR);
  }

  private static final AgentPropagation.ContextVisitor<Map<String, String>> MAP_VISITOR =
      new MapContextVisitor();

  private static final class MapContextVisitor
      implements AgentPropagation.ContextVisitor<Map<String, String>> {
    @Override
    public void forEachKey(Map<String, String> carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, String> entry : carrier.entrySet()) {
        if (!classifier.accept(entry.getKey(), entry.getValue())) {
          return;
        }
      }
    }
  }
}
