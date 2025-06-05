package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.core.BlackholeWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.TraceCounters;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.ParametersAreNonnullByDefault;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 120, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 120, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressForbidden
public class InjectorBenchmark {
  @Param({"datadog", "b3", "datadog,b3", "datadog:x-dth", "datadog:x-dth-mod"})
  String injectPropagationStyles;

  static String dup(String input) {
    return new String(input.toLowerCase().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
  }

  Map<String, String> headers;
  HttpCodec.Injector injector;
  DDTraceId traceId;
  long spanId;
  CoreTracer tracer;
  DDSpanContext spanContext;
  PropagationTags propagationTags;
  boolean modifyPropagationTags = false;

  @Setup(Level.Trial)
  public void setUp(Blackhole blackhole) {
    headers = new HashMap<>();
    String[] propagationsAndFeatures = injectPropagationStyles.split(",");
    StringBuilder propagations = new StringBuilder();
    boolean addSeparator = false;
    for (String pAndA : propagationsAndFeatures) {
      if (addSeparator) {
        propagations.append(',');
      } else {
        addSeparator = true;
      }
      String[] propagationAndFeatures = pAndA.split(":");
      propagations.append(propagationAndFeatures[0]);
      for (int i = 1; i < propagationAndFeatures.length; i++) {
        String feature = propagationAndFeatures[i];
        switch (feature) {
          case "x-dth":
            propagationTags =
                PropagationTags.factory()
                    .fromHeaderValue(
                        PropagationTags.HeaderType.DATADOG,
                        "_dd.p.anytag=value,_dd.p.dm=934086a686-4");
            break;
          case "x-dth-mod":
            propagationTags =
                PropagationTags.factory()
                    .fromHeaderValue(
                        PropagationTags.HeaderType.DATADOG,
                        "_dd.p.anytag=value,_dd.p.dm=934086a686-4");
            modifyPropagationTags = true;
            break;
          default:
            System.out.println("Unknown benchmark feature " + feature + ". Will be ignored!");
        }
      }
    }

    System.setProperty("dd.propagation.style.extract", propagations.toString());
    injector =
        HttpCodec.createInjector(
            Config.get(), Config.get().getTracePropagationStylesToInject(), Collections.emptyMap());

    traceId = DDTraceId.from("12345");
    spanId = DDSpanId.from("23456");

    tracer =
        CoreTracer.builder()
            .writer(new BlackholeWriter(blackhole, new TraceCounters(), 0))
            .strictTraceWrites(false)
            .build();

    spanContext =
        new DDSpanContext(
            traceId,
            spanId,
            DDSpanId.ZERO,
            "",
            "service",
            "operation",
            "resource",
            0,
            "origin",
            Collections.<String, String>emptyMap(),
            false,
            "type",
            0,
            tracer.createTraceCollector(traceId),
            null,
            null,
            null,
            false,
            propagationTags);
  }

  int mechanism = 0;

  @Benchmark
  public void injectContext(Blackhole blackhole) {
    injector.inject(spanContext, headers, MAP_SETTER);
    blackhole.consume(headers);
    if (modifyPropagationTags) {
      int sm = mechanism = (mechanism + 1) % 4;
      propagationTags.updateTraceSamplingPriority(1, sm);
    }
  }

  private static final AgentPropagation.ContextVisitor<Map<String, String>> MAP_VISITOR =
      new MapContextVisitor<>();

  private static final class MapContextVisitor<T extends Map<String, String>>
      implements AgentPropagation.ContextVisitor<T> {

    @Override
    public void forEachKey(T carrier, AgentPropagation.KeyClassifier classifier) {
      for (Map.Entry<String, ?> entry : carrier.entrySet()) {
        if (null != entry.getValue()
            && !classifier.accept(entry.getKey(), entry.getValue().toString())) {
          return;
        }
      }
    }
  }

  private static final CarrierSetter<Map<String, String>> MAP_SETTER = new MapContextSetter<>();

  @ParametersAreNonnullByDefault
  private static final class MapContextSetter<T extends Map<String, String>>
      implements CarrierSetter<T> {
    @Override
    public void set(T carrier, String key, String value) {
      carrier.put(key, value);
    }
  }
}
