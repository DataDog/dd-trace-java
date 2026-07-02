package datadog.trace.bootstrap.instrumentation.classloading;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClassDefiningTest {

  @BeforeEach
  void resetStaticState() throws Exception {
    Field hasObserver = ClassDefining.class.getDeclaredField("HAS_OBSERVER");
    hasObserver.setAccessible(true);
    ((AtomicBoolean) hasObserver.get(null)).set(false);

    Field observer = ClassDefining.class.getDeclaredField("OBSERVER");
    observer.setAccessible(true);
    observer.set(null, (ClassDefining.Observer) (loader, bytecode, offset, length) -> {});
  }

  @Test
  void beginWithNoObserverIsNoOp() {
    assertDoesNotThrow(() -> ClassDefining.begin(null, new byte[10], 0, 10));
  }

  @Test
  void beginCallsRegisteredObserverOnEachInvocation() {
    AtomicInteger calls = new AtomicInteger();
    ClassDefining.observe((loader, bytecode, offset, length) -> calls.incrementAndGet());

    ClassDefining.begin(null, new byte[4], 0, 4);
    ClassDefining.begin(null, new byte[4], 0, 4);

    assertEquals(2, calls.get());
  }

  @Test
  void observerReceivesCorrectArguments() {
    ClassLoader loader = ClassLoader.getSystemClassLoader();
    byte[] bytecode = {1, 2, 3, 4, 5};

    ClassLoader[] capturedLoader = new ClassLoader[1];
    byte[][] capturedBytecode = new byte[1][];
    int[] capturedOffset = new int[1];
    int[] capturedLength = new int[1];

    ClassDefining.observe(
        (l, b, o, len) -> {
          capturedLoader[0] = l;
          capturedBytecode[0] = b;
          capturedOffset[0] = o;
          capturedLength[0] = len;
        });

    ClassDefining.begin(loader, bytecode, 1, 3);

    assertSame(loader, capturedLoader[0]);
    assertSame(bytecode, capturedBytecode[0]);
    assertEquals(1, capturedOffset[0]);
    assertEquals(3, capturedLength[0]);
  }

  @Test
  void secondObserveCallIsIgnoredFirstObserverRemains() {
    AtomicInteger firstCalls = new AtomicInteger();
    AtomicInteger secondCalls = new AtomicInteger();

    ClassDefining.observe((loader, bytecode, offset, length) -> firstCalls.incrementAndGet());
    ClassDefining.observe((loader, bytecode, offset, length) -> secondCalls.incrementAndGet());

    ClassDefining.begin(null, new byte[4], 0, 4);

    assertEquals(1, firstCalls.get());
    assertEquals(0, secondCalls.get());
  }

  @Test
  void observeIsIdempotentWhenCalledWithSameObserverRepeatedly() {
    AtomicInteger calls = new AtomicInteger();
    ClassDefining.Observer observer = (l, b, o, len) -> calls.incrementAndGet();

    for (int i = 0; i < 5; i++) {
      ClassDefining.observe(observer);
    }

    ClassDefining.begin(null, new byte[1], 0, 1);

    assertEquals(1, calls.get());
  }
}
