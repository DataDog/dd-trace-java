package datadog.context.propagation;

import static datadog.context.Context.root;
import static datadog.context.propagation.Concern.DEFAULT_PRIORITY;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import javax.annotation.ParametersAreNonnullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PropagatorsTest {
  static final MapCarrierAccessor ACCESSOR = new MapCarrierAccessor();

  static final Concern TRACING = Concern.named("tracing");
  static final ContextKey<String> TRACING_KEY = ContextKey.named("tracing");
  static final Propagator TRACING_PROPAGATOR = new BasicPropagator(TRACING_KEY, "tracing");

  static final Concern IAST = Concern.named("iast");
  static final ContextKey<String> IAST_KEY = ContextKey.named("iast");
  static final Propagator IAST_PROPAGATOR = new BasicPropagator(IAST_KEY, "iast");

  static final Concern DEBUGGER = Concern.withPriority("debugger", DEFAULT_PRIORITY - 10);
  static final ContextKey<String> DEBUGGER_KEY = ContextKey.named("debugger");
  static final DependentPropagator DEBUGGER_PROPAGATOR =
      new DependentPropagator(DEBUGGER_KEY, "debugger", TRACING_KEY);

  static final Concern PROFILING = Concern.withPriority("profiling", DEFAULT_PRIORITY + 10);
  static final ContextKey<String> PROFILING_KEY = ContextKey.named("profiling");
  static final DependentPropagator PROFILING_PROPAGATOR =
      new DependentPropagator(PROFILING_KEY, "profiling", TRACING_KEY);

  static final Context CONTEXT =
      root()
          .with(TRACING_KEY, "sampled")
          .with(IAST_KEY, "standalone")
          .with(DEBUGGER_KEY, "debug")
          .with(PROFILING_KEY, "profile");

  @ParametersAreNonnullByDefault
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

  static class BasicPropagator implements Propagator {
    private final ContextKey<String> contextKey;
    private final String carrierKey;

    public BasicPropagator(ContextKey<String> contextKey, String carrierKey) {
      this.contextKey = requireNonNull(contextKey);
      this.carrierKey = requireNonNull(carrierKey);
    }

    @Override
    public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
      String value = context.get(this.contextKey);
      if (value != null) {
        setter.set(carrier, this.carrierKey, value);
      }
    }

    @Override
    public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
      String[] valueRef = new String[1];
      visitor.forEachKeyValue(
          carrier,
          (key, value) -> {
            if (this.carrierKey.equals(key)) {
              valueRef[0] = value;
            }
          });
      if (valueRef[0] != null) {
        context = context.with(this.contextKey, valueRef[0]);
      }
      return context;
    }
  }

  static class DependentPropagator extends BasicPropagator implements Propagator {
    private final ContextKey<String> requiredContextKey;
    private boolean keyFound;

    public DependentPropagator(
        ContextKey<String> contextKey, String carrierKey, ContextKey<String> requiredContextKey) {
      super(contextKey, carrierKey);
      this.requiredContextKey = requiredContextKey;
      this.keyFound = false;
    }

    @Override
    public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
      this.keyFound = context.get(this.requiredContextKey) != null;
      return super.extract(context, carrier, visitor);
    }

    public void reset() {
      this.keyFound = false;
    }
  }

  @BeforeEach
  @AfterEach
  void resetPropagators() {
    Propagators.reset();
    DEBUGGER_PROPAGATOR.reset();
    PROFILING_PROPAGATOR.reset();
  }

  @Test
  void testDefaultPropagator() {
    Propagator noopPropagator = Propagators.defaultPropagator();
    assertNotNull(
        noopPropagator, "Default propagator should not be null when no propagator is registered");
    assertInjectExtractContext(CONTEXT, noopPropagator);

    Propagators.register(TRACING, TRACING_PROPAGATOR);
    Propagator single = Propagators.defaultPropagator();
    assertInjectExtractContext(CONTEXT, single, TRACING_KEY);

    Propagators.register(IAST, IAST_PROPAGATOR, false);
    Propagators.register(DEBUGGER, DEBUGGER_PROPAGATOR);
    Propagators.register(PROFILING, PROFILING_PROPAGATOR);
    Propagator composite = Propagators.defaultPropagator();
    assertDoNotInjectExtractContext(CONTEXT, composite, IAST_KEY);
    assertInjectExtractContext(CONTEXT, composite, TRACING_KEY, DEBUGGER_KEY, PROFILING_KEY);
    assertFalse(
        DEBUGGER_PROPAGATOR.keyFound,
        "Debugger propagator should have run before tracing propagator");
    assertTrue(
        PROFILING_PROPAGATOR.keyFound,
        "Profiling propagator should have run after tracing propagator");

    Propagator cached = Propagators.defaultPropagator();
    assertEquals(composite, cached, "default propagator should be cached");
  }

  @Test
  void testConcurrentRegisterAndDefaultPropagator() throws InterruptedException {
    int count = 500;
    int readerCount = 4;
    AtomicBoolean registering = new AtomicBoolean(true);
    AtomicReference<Throwable> readerFailure = new AtomicReference<>();
    CountDownLatch readersReady = new CountDownLatch(readerCount);
    CountDownLatch startReading = new CountDownLatch(1);
    Thread[] readers = new Thread[readerCount];
    for (int r = 0; r < readerCount; r++) {
      readers[r] =
          new Thread(() -> {
            try {
              readersReady.countDown();
              startReading.await();
              do {
                if (Propagators.defaultPropagator() == null) {
                  readerFailure.compareAndSet(
                      null, new AssertionError("Default propagator should never be null"));
                }
              } while (registering.get());
            } catch (Throwable t) {
              readerFailure.compareAndSet(null, t);
            }
          });
      readers[r].start();
    }
    assertTrue(readersReady.await(10, SECONDS), "Reader threads should have started");
    // Release readers together so they race the first lazy build, then race registrations
    startReading.countDown();
    Context context = root();
    for (int i = 0; i < count; i++) {
      ContextKey<String> key = ContextKey.named("concurrent-" + i);
      context = context.with(key, "value");
      Propagators.register(
          Concern.named("concurrent-" + i), new BasicPropagator(key, "concurrent-" + i));
    }
    registering.set(false);
    for (Thread reader : readers) {
      reader.join(SECONDS.toMillis(10));
      assertFalse(reader.isAlive(), "Reader thread should have stopped");
    }
    assertNull(readerFailure.get(), "Reader threads should not fail");

    Map<String, String> carrier = new HashMap<>();
    Propagators.defaultPropagator().inject(context, carrier, ACCESSOR);
    assertEquals(count, carrier.size(), "All registered propagators should be in the default one");
  }

  @Test
  void testForConcern() {
    // Test when not registered
    Propagator propagator = Propagators.forConcern(TRACING);
    assertNotNull(propagator, "Propagator should not be null when no propagator is registered");
    assertNoopPropagator(propagator);
    // Test when registered
    Propagators.register(TRACING, TRACING_PROPAGATOR);
    propagator = Propagators.forConcern(TRACING);
    assertNotNull(propagator, "Propagator should not be null when registered");
    assertInjectExtractContext(CONTEXT, propagator, TRACING_KEY);
  }

  @Test
  void testForConcerns() {
    // Test when none registered
    Propagator propagator = Propagators.forConcerns(TRACING, IAST);
    assertNotNull(propagator, "Propagator should not be null when no propagator is registered");
    assertNoopPropagator(propagator);
    // Test when only one is registered
    Propagators.register(TRACING, TRACING_PROPAGATOR);
    propagator = Propagators.forConcerns(TRACING, IAST);
    assertNotNull(propagator, "Propagator should not be null when one is registered");
    assertInjectExtractContext(CONTEXT, propagator, TRACING_KEY);
    // Test when all registered
    Propagators.register(IAST, IAST_PROPAGATOR);
    propagator = Propagators.forConcerns(TRACING, IAST);
    assertNotNull(propagator, "Propagator should not be null when all are registered");
    assertInjectExtractContext(CONTEXT, propagator, TRACING_KEY, IAST_KEY);
    // Test propagator order follow the given concerns order despite concern priority
    Propagators.register(DEBUGGER, DEBUGGER_PROPAGATOR);
    Propagators.register(PROFILING, PROFILING_PROPAGATOR);
    propagator = Propagators.forConcerns(PROFILING, TRACING, DEBUGGER);
    assertInjectExtractContext(CONTEXT, propagator, PROFILING_KEY, TRACING_KEY, DEBUGGER_KEY);
    assertFalse(
        PROFILING_PROPAGATOR.keyFound,
        "Profiling propagator should have run before tracing propagator");
    assertTrue(
        DEBUGGER_PROPAGATOR.keyFound,
        "Debugger propagator should have run before tracing propagator");
  }

  @Test
  void testNoopPropagator() {
    Propagator noopPropagator = Propagators.noop();
    assertNotNull(noopPropagator, "noop propagator should not be null");
    assertNoopPropagator(noopPropagator);
  }

  void assertNoopPropagator(Propagator noopPropagator) {
    Map<String, String> carrier = new HashMap<>();
    noopPropagator.inject(CONTEXT, carrier, ACCESSOR);
    assertTrue(carrier.isEmpty(), "carrier should be empty");
    Context extracted = noopPropagator.extract(root(), carrier, ACCESSOR);
    assertEquals(root(), extracted, "extracted context should be empty");
  }

  void assertInjectExtractContext(Context context, Propagator propagator, ContextKey<?>... keys) {
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(context, carrier, ACCESSOR);
    Context extracted = propagator.extract(root(), carrier, ACCESSOR);
    for (ContextKey<?> key : keys) {
      assertEquals(
          context.get(key), extracted.get(key), "Key " + key + " not injected nor extracted");
    }
  }

  private void assertDoNotInjectExtractContext(
      Context context, Propagator propagator, ContextKey<?>... keys) {
    Map<String, String> carrier = new HashMap<>();
    propagator.inject(context, carrier, ACCESSOR);
    Context extracted = propagator.extract(root(), carrier, ACCESSOR);
    for (ContextKey<?> key : keys) {
      assertNull(extracted.get(key), "Key " + key + " was injected");
    }
  }
}
