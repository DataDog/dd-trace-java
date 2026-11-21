package datadog.context.propagation;

import static datadog.context.Context.root;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

class CompositePropagatorKeysTest {

  /** Records the keys it sees; its own keys can be swapped, as a config change would. */
  static final class RecordingPropagator implements Propagator {
    CarrierKeys keys;
    final List<String> seen = new ArrayList<>();

    RecordingPropagator(CarrierKeys keys) {
      this.keys = keys;
    }

    @Override
    public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {}

    @Override
    public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
      visitor.forEachKeyValue(carrier, (key, value) -> seen.add(key));
      return context;
    }

    @Override
    public CarrierKeys keys() {
      return keys;
    }
  }

  /** A map visitor that skips keys not in the keys it's given, as a filtering carrier would. */
  static final class FilteringMapVisitor implements CarrierVisitor<Map<String, String>> {
    CarrierKeys lastKeys;

    @Override
    public void forEachKeyValue(Map<String, String> carrier, BiConsumer<String, String> visitor) {
      carrier.forEach(visitor);
    }

    @Override
    public void forEachKeyValue(
        Map<String, String> carrier, CarrierKeys keys, BiConsumer<String, String> visitor) {
      lastKeys = keys;
      carrier.forEach((key, value) -> {
        if (keys.contains(key)) {
          visitor.accept(key, value);
        }
      });
    }
  }

  private static Map<String, String> carrier() {
    Map<String, String> carrier = new LinkedHashMap<>();
    carrier.put("traceparent", "tp");
    carrier.put("baggage", "b");
    carrier.put("content-type", "text/plain");
    return carrier;
  }

  @Test
  void keysAreTheUnionOfThePropagatorsKeys() {
    RecordingPropagator tracing = new RecordingPropagator(CarrierKeys.of("traceparent"));
    RecordingPropagator baggage = new RecordingPropagator(CarrierKeys.of("baggage"));
    CompositePropagator composite = new CompositePropagator(new Propagator[] {tracing, baggage});

    CarrierKeys keys = composite.keys();
    assertTrue(keys.contains("traceparent"));
    assertTrue(keys.contains("baggage"));
    assertFalse(keys.contains("content-type"));
    assertSame(keys, composite.keys(), "rebuilt without a change");
  }

  @Test
  void extractionPassesTheKeysToTheVisitor() {
    RecordingPropagator tracing = new RecordingPropagator(CarrierKeys.of("traceparent"));
    RecordingPropagator baggage = new RecordingPropagator(CarrierKeys.of("baggage"));
    CompositePropagator composite = new CompositePropagator(new Propagator[] {tracing, baggage});
    FilteringMapVisitor visitor = new FilteringMapVisitor();

    composite.extract(root(), carrier(), visitor);

    assertSame(composite.keys(), visitor.lastKeys);
    // each propagator sees the keys any of them reads, and none that no propagator reads
    assertEquals(2, tracing.seen.size());
    assertTrue(tracing.seen.contains("traceparent") && tracing.seen.contains("baggage"));
    assertEquals(tracing.seen, baggage.seen);
  }

  @Test
  void aVisitorThatDoesNotFilterStillVisitsEverything() {
    RecordingPropagator tracing = new RecordingPropagator(CarrierKeys.of("traceparent"));
    CompositePropagator composite = new CompositePropagator(new Propagator[] {tracing});

    composite.extract(root(), carrier(), (carrier, visitor) -> carrier.forEach(visitor));

    assertEquals(3, tracing.seen.size());
  }

  @Test
  void aPropagatorWithoutDeclaredKeysKeepsEveryKey() {
    RecordingPropagator tracing = new RecordingPropagator(CarrierKeys.of("traceparent"));
    Propagator undeclared =
        new Propagator() {
          @Override
          public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {}

          @Override
          public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
            return context;
          }
        };
    CompositePropagator composite = new CompositePropagator(new Propagator[] {tracing, undeclared});
    FilteringMapVisitor visitor = new FilteringMapVisitor();

    composite.extract(root(), carrier(), visitor);

    assertTrue(composite.keys().isAll());
    assertEquals(3, tracing.seen.size());
  }

  @Test
  void keysAreRebuiltWhenAPropagatorsKeysChange() {
    RecordingPropagator tracing = new RecordingPropagator(CarrierKeys.of("traceparent"));
    CompositePropagator composite = new CompositePropagator(new Propagator[] {tracing});
    CarrierKeys before = composite.keys();

    tracing.keys = CarrierKeys.of("traceparent", "x-custom-header");
    CarrierKeys after = composite.keys();

    assertNotSame(before, after);
    assertTrue(after.contains("x-custom-header"));
    assertFalse(before.contains("x-custom-header"));
  }

  @Test
  void noopPropagatorReadsNoKeys() {
    assertFalse(NoopPropagator.INSTANCE.keys().isAll());
    assertFalse(NoopPropagator.INSTANCE.keys().contains("traceparent"));
  }
}
