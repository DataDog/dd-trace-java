package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.empty;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextStorageTest {
  @BeforeAll
  static void setup() {
    DefaultContextBinder.register();
  }

  @BeforeEach
  void init() {
    // Ensure no current context prior starting test
    assertEquals(empty(), current());
  }

  @Test
  void testContextAttachment() {
    Context context1 = empty().with(STRING_KEY, "value1");
    try (ContextScope ignored = context1.makeCurrent()) {
      // Test context1 is attached
      assertEquals(context1, current());
      Context context2 = context1.with(STRING_KEY, "value2");
      try (ContextScope ignored1 = context2.makeCurrent()) {
        // Test context2 is attached
        assertEquals(context2, current());
      }
      // Test context1 is restored
      assertEquals(context1, current());
    }
  }

  @Test
  void testNullContextAttachment() {
    assertDoesNotThrow(
        () -> {
          //noinspection DataFlowIssue
          ContextScope contextScope = ContextStorage.get().attach(null);
          assertEquals(Context.empty(), Context.current());
          contextScope.close();
        });
  }

  @Test
  void testAttachSameContextMultipleTimes() {
    Context context = empty().with(STRING_KEY, "value1");
    try (ContextScope ignored = context.makeCurrent()) {
      assertEquals(context, current());
      try (ContextScope ignored1 = context.makeCurrent()) {
        try (ContextScope ignored2 = context.makeCurrent()) {
          assertEquals(context, current());
        }
        // Test closing a scope on the current context should not deactivate it if activated
        // multiple times
        assertEquals(context, current());
      }
    }
    // Test closing the same number of scope as activation should deactivate the context
    assertEquals(empty(), current());
  }

  @Test
  void testOnlyCurrentScopeCanBeClosed() {
    Context context1 = empty().with(STRING_KEY, "value1");
    try (ContextScope scope1 = context1.makeCurrent()) {
      Context context2 = context1.with(STRING_KEY, "value2");
      try (ContextScope ignored = context2.makeCurrent()) {
        // Try closing the non-current scope
        scope1.close();
        // Test context2 is still attached
        assertEquals(context2, current());
      }
      // Test context1 is restored
      assertEquals(context1, current());
    }
  }

  @Test
  void testClosingMultipleTimes() {
    Context context1 = empty().with(STRING_KEY, "value1");
    try (ContextScope ignored = context1.makeCurrent()) {
      Context context2 = context1.with(STRING_KEY, "value2");
      ContextScope scope = context2.makeCurrent();
      // Test current context
      assertEquals(context2, current());
      // Test current context deactivation
      scope.close();
      assertEquals(context1, current());
      // Test multiple context deactivations don’t change current context
      scope.close();
      assertEquals(context1, current());
    }
  }

  @Test
  void testThreadIndependence() {
    /*
     * This test has 2 executors in addition to the main thread.
     * They are synchronized using a Phaser, and arrived before each assert phase.
     * If an assert fails in of one the executor, the executor is "deregister" to unblock the test,
     * and the exception is restored at the end of the test using "Future.get()".
     */
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Phaser phaser = new Phaser(3);
    /*
     * Create first executor.
     */
    Future<?> future1 =
        executor.submit(
            () -> {
              try {
                // Fist step: check empty context
                phaser.arriveAndAwaitAdvance();
                assertEquals(empty(), current());
                // Second step: set context on first executor
                Context context1 = empty().with(STRING_KEY, "executor1");
                try (ContextScope ignored1 = context1.makeCurrent()) {
                  phaser.arriveAndAwaitAdvance();
                  assertEquals(context1, current());
                  // Third step: set context on second executor
                  phaser.arriveAndAwaitAdvance();
                  assertEquals(context1, current());
                  // Fourth step: set child context on first executor
                  Context context11 = context1.with(STRING_KEY, "executor1.1");
                  try (ContextScope ignored11 = context11.makeCurrent()) {
                    phaser.arriveAndAwaitAdvance();
                    assertEquals(context11, current());
                  }
                }
              } finally {
                // Complete the execution
                phaser.arriveAndDeregister();
              }
            });
    /*
     * Create second executor.
     */
    Future<?> future2 =
        executor.submit(
            () -> {
              try {
                // First step: check empty context
                phaser.arriveAndAwaitAdvance();
                assertEquals(empty(), current());
                // Second step: set context on first executor
                phaser.arriveAndAwaitAdvance();
                assertEquals(empty(), current());
                // Third step: set context on second executor
                Context context2 = empty().with(STRING_KEY, "executor2");
                try (ContextScope ignored2 = context2.makeCurrent()) {
                  phaser.arriveAndAwaitAdvance();
                  assertEquals(context2, current());
                  // Fourth step: set child context on first executor
                  phaser.arriveAndAwaitAdvance();
                  assertEquals(context2, current());
                }
              } finally {
                // Complete the execution
                phaser.arriveAndDeregister();
              }
            });
    /*
     * Run main thread.
     */
    // First step: check empty context
    phaser.arriveAndAwaitAdvance();
    assertEquals(empty(), current());
    // Second step: set context on first executor
    phaser.arriveAndAwaitAdvance();
    assertEquals(empty(), current());
    // Third step: set context on second executor
    phaser.arriveAndAwaitAdvance();
    assertEquals(empty(), current());
    // Fourth step: set child context on first executor
    phaser.arriveAndAwaitAdvance();
    assertEquals(empty(), current());
    // Complete execution and wait for the others
    phaser.arriveAndAwaitAdvance();
    executor.shutdown();
    // Check any test error in executors
    assertDoesNotThrow(() -> future1.get());
    assertDoesNotThrow(() -> future2.get());
  }

  @Test
  void testNonThreadInheritance() {
    Context context = empty().with(STRING_KEY, "value");
    try (ContextScope ignored = context.makeCurrent()) {
      // Check new thread don't inherit from current context
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Future<?> future = executor.submit(() -> assertEquals(empty(), current()));
      assertDoesNotThrow(() -> future.get());
    }
  }

  @AfterEach
  void tearDown() {
    // Ensure no current context after ending test
    assertEquals(empty(), current());
  }
}
