package datadog.context;

import static datadog.context.Context.current;
import static datadog.context.Context.root;
import static datadog.context.ContextTest.STRING_KEY;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ContextManagerTest {
  @BeforeEach
  void init() {
    // Ensure no current context prior starting test
    assertEquals(root(), current());
  }

  @Test
  void testContextAttachment() {
    Context context1 = root().with(STRING_KEY, "value1");
    try (ContextScope scope1 = context1.attach()) {
      // Test context1 is attached
      assertEquals(context1, current());
      assertEquals(context1, scope1.context());
      Context context2 = context1.with(STRING_KEY, "value2");
      try (ContextScope scope2 = context2.attach()) {
        // Test context2 is attached
        assertEquals(context2, current());
        assertEquals(context2, scope2.context());
        // Can still access context1 from its scope
        assertEquals(context1, scope1.context());
      }
      // Test context1 is restored
      assertEquals(context1, current());
    }
  }

  @Test
  void testContextSwapping() {
    Context context1 = root().with(STRING_KEY, "value1");
    assertEquals(root(), current());
    assertEquals(root(), context1.swap());
    // Test context1 is attached
    Context context2 = context1.with(STRING_KEY, "value2");
    assertEquals(context1, current());
    assertEquals(context1, context2.swap());
    // Test context2 is attached
    assertEquals(context2, current());
    assertEquals(context2, root().swap());
    // Test we're now context-less
    assertEquals(root(), current());
  }

  @Test
  void testAttachSameContextMultipleTimes() {
    Context context = root().with(STRING_KEY, "value1");
    try (ContextScope ignored1 = context.attach()) {
      assertEquals(context, current());
      try (ContextScope ignored2 = context.attach()) {
        try (ContextScope ignored3 = context.attach()) {
          assertEquals(context, current());
        }
        // Test closing a scope on the current context should not deactivate it if activated
        // multiple times
        assertEquals(context, current());
      }
    }
    // Test closing the same number of scope as activation should deactivate the context
    assertEquals(root(), current());
  }

  @Test
  void testOnlyCurrentScopeCanBeClosed() {
    Context context1 = root().with(STRING_KEY, "value1");
    try (ContextScope scope1 = context1.attach()) {
      Context context2 = context1.with(STRING_KEY, "value2");
      try (ContextScope ignored = context2.attach()) {
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
    Context context1 = root().with(STRING_KEY, "value1");
    try (ContextScope ignored = context1.attach()) {
      Context context2 = context1.with(STRING_KEY, "value2");
      ContextScope scope = context2.attach();
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
                assertEquals(root(), current());
                // Second step: set context on first executor
                Context context1 = root().with(STRING_KEY, "executor1");
                try (ContextScope ignored1 = context1.attach()) {
                  phaser.arriveAndAwaitAdvance();
                  assertEquals(context1, current());
                  // Third step: set context on second executor
                  phaser.arriveAndAwaitAdvance();
                  assertEquals(context1, current());
                  // Fourth step: set child context on first executor
                  Context context11 = context1.with(STRING_KEY, "executor1.1");
                  try (ContextScope ignored11 = context11.attach()) {
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
                assertEquals(root(), current());
                // Second step: set context on first executor
                phaser.arriveAndAwaitAdvance();
                assertEquals(root(), current());
                // Third step: set context on second executor
                Context context2 = root().with(STRING_KEY, "executor2");
                try (ContextScope ignored2 = context2.attach()) {
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
    assertEquals(root(), current());
    // Second step: set context on first executor
    phaser.arriveAndAwaitAdvance();
    assertEquals(root(), current());
    // Third step: set context on second executor
    phaser.arriveAndAwaitAdvance();
    assertEquals(root(), current());
    // Fourth step: set child context on first executor
    phaser.arriveAndAwaitAdvance();
    assertEquals(root(), current());
    // Complete execution and wait for the others
    phaser.arriveAndAwaitAdvance();
    executor.shutdown();
    // Check any test error in executors
    assertDoesNotThrow(() -> future1.get());
    assertDoesNotThrow(() -> future2.get());
  }

  @Test
  void testNonThreadInheritance() {
    Context context = root().with(STRING_KEY, "value");
    try (ContextScope ignored = context.attach()) {
      // Check new thread don't inherit from current context
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Future<?> future = executor.submit(() -> assertEquals(root(), current()));
      assertDoesNotThrow(() -> future.get());
    }
  }

  @AfterEach
  void tearDown() {
    // Ensure no current context after ending test
    assertEquals(root(), current());
  }
}
