package datadog.trace.core.baggage;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
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
@Warmup(iterations = 4, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 4, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class BaggagePropagatorBenchmark {
  // Simulates different baggage header values

  private static final Logger logger = Logger.getLogger(BaggagePropagatorBenchmark.class.getName());

  @Param("ignore this value")
  String baggageHeaderValue;

  private BaggagePropagator propagator;
  private Context context;
  private Map<String, String> carrier;
  private CarrierSetter<Map<String, String>> setter;
  private CarrierVisitor<Map<String, String>> visitor;
  private boolean test;

  static class MapCarrierAccessor
      implements CarrierSetter<Map<String, String>>, CarrierVisitor<Map<String, String>> {
    @Override
    public void set(Map<String, String> carrier, String key, String value) {
      if (carrier != null && key != null && value != null) {
        carrier.put(key, value);
      }
    }

    @Override
    public void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> visitor) {
      carrier.forEach(visitor);
    }
  }

  @Setup(Level.Trial)
  public void setUp() {
    propagator = new BaggagePropagator(true, true);
    context = Context.root();

    StringBuilder sb = new StringBuilder(8192);
    for (int i = 0; i < 8192; i++) {
      sb.append('a');
    }
    final String aKey = "key1=" + sb.toString();
    //    final String aKey = "key1=Amélie";

    baggageHeaderValue = aKey;
    carrier = new HashMap<>();
    carrier.put(BaggagePropagator.BAGGAGE_KEY, baggageHeaderValue);
    visitor = ContextVisitors.stringValuesMap();
    setter = new MapCarrierAccessor();
    test = false;
  }

  @Benchmark
  public void propagate(Blackhole blackhole) {
    Context extractedContext = propagator.extract(context, carrier, visitor);
    carrier = new HashMap<>();
    //    blackhole.consume(baggage.getW3cHeader());
    //    blackhole.consume(extractedContext);
    propagator.inject(extractedContext, carrier, setter);
    //    if (!test){
    //      Baggage baggage = Baggage.fromContext(extractedContext);
    //      System.out.println("w3c header: " + baggage.getW3cHeader());
    //      Map<String, String> baggageMap =  baggage.asMap();
    //      for(String key : baggageMap.keySet()){
    //        System.out.println(key + ":" + baggageMap.get(key));
    //      }
    //      System.out.println("Carrier.size: " + carrier.size());
    //      for (String key : carrier.keySet()){
    //        System.out.println("Carrier.keyset: " + key);
    //        System.out.println("Carrier: " + carrier.get(key));
    //      }
    //      test = true;
    //    }

    //    blackhole.consume(baggage);
  }
}
