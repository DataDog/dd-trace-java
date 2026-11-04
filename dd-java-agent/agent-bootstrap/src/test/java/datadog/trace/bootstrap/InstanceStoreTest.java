package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.test.util.DDJavaSpecification;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class InstanceStoreTest extends DDJavaSpecification {

  private static final AtomicInteger COUNTER = new AtomicInteger();

  // Since the InstanceStore is a per Class singleton, every test case needs new keys
  private static String nextKey() {
    return "key-" + COUNTER.incrementAndGet();
  }

  @Test
  void testBasicOperation() {
    // setup
    InstanceStore<Some> someStore = InstanceStore.of(Some.class);
    Some some1 = new Some();
    Some some2 = new Some();
    String key = nextKey();
    Some current;

    // when
    someStore.put(key, some1);
    current = someStore.get(key);

    // then
    assertSame(some1, current);

    // when
    someStore.put(key, some2);
    current = someStore.get(key);

    // then
    assertSame(some2, current);

    // when
    someStore.remove(key);
    current = someStore.get(key);

    // then
    assertNull(current);
  }

  @Test
  void testReturnsExistingStore() {
    // setup
    Some some1 = new Some();
    String key = nextKey();
    InstanceStore.of(Some.class).put(key, some1);

    // when
    Some current = InstanceStore.of(Some.class).getOrCreate(key, Some::new);

    // then
    assertSame(some1, current);

    // when
    current = InstanceStore.of(Some.class).getOrCreate(key, Some::new);

    // then
    assertSame(some1, current);
  }

  @Test
  void testConditionallySetFirstValue() {
    InstanceStore<Some> someStore = InstanceStore.of(Some.class);
    Some some1 = new Some();
    String key = nextKey();

    // when
    Some current = someStore.getOrCreate(key, () -> some1);

    // then
    assertSame(some1, current);

    // when
    current = someStore.getOrCreate(key, Some::new);

    // then
    assertSame(some1, current);
  }

  @Test
  void testFactoryIsNotCalledWhenValueExists() {
    InstanceStore<Some> someStore = InstanceStore.of(Some.class);
    Some some1 = new Some();
    AtomicInteger invocations = new AtomicInteger();
    String key = nextKey();
    someStore.put(key, some1);

    // when
    Some current = someStore.getOrCreate(key, new Creator(invocations));

    // then
    assertSame(some1, current);
    assertEquals(0, invocations.get());
  }

  @Test
  void testFactoryIsCalledOnlyOnceForMultipleInvocations() {
    InstanceStore<Some> someStore = InstanceStore.of(Some.class);
    Some some1 = new Some();
    AtomicInteger invocations = new AtomicInteger();
    String key = nextKey();

    // when
    Some current = someStore.getOrCreate(key, new Creator(invocations, some1));

    // then
    assertSame(some1, current);
    assertEquals(1, invocations.get());

    // when
    current = someStore.getOrCreate(key, new Creator(invocations));

    // then
    assertSame(some1, current);
    assertEquals(1, invocations.get());
  }

  static class Some {}

  static class Creator implements Supplier<Some> {
    private final AtomicInteger invocations;
    private final Some some;

    Creator(AtomicInteger invocations, Some some) {
      this.invocations = invocations;
      this.some = some;
    }

    Creator(AtomicInteger invocations) {
      this(invocations, new Some());
    }

    @Override
    public Some get() {
      invocations.incrementAndGet();
      return some;
    }
  }
}
