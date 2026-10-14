package datadog.trace.core.scopemanager;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.api.Stateful;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.ScopeManagerTestBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class SharedScopeStackTest extends DDCoreJavaSpecification {
  private static final ContextKey<String> KEY = ContextKey.named("shared-scope-stack-test");

  private final Context base = Context.root().with(KEY, "base");

  private CoreTracer tracer;
  private ContinuableScopeManager scopeManager;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().writer(new ListWriter()).build();
    scopeManager = ScopeManagerTestBridge.getScopeManager(tracer);
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @Test
  void scopeOpenedOnOneThreadIsVisibleAndClosableOnAnother() throws Exception {
    Context shared = scopeManager.sharedContextStack(base);
    Context opened = base.with(KEY, "opened");
    AtomicReference<ContextScope> scope = new AtomicReference<>();

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          scope.set(scopeManager.attach(opened));
          scopeManager.swap(original);
        });
    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          assertSame(opened, scopeManager.currentContext());
          scope.get().close();
          assertSame(base, scopeManager.currentContext());
          scopeManager.swap(original);
        });
  }

  @Test
  void rootSeedStartsEmpty() throws Exception {
    Context shared = scopeManager.sharedContextStack(Context.root());

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          assertNull(scopeManager.active());
          assertEquals(0, scopeManager.scopeStack().depth());
          scopeManager.swap(original);
        });
  }

  @Test
  void scopeClosedElsewhereIsRemovedWhenSwappedIn() throws Exception {
    Context shared = scopeManager.sharedContextStack(base);
    AtomicReference<ContextScope> scope = new AtomicReference<>();

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          scope.set(scopeManager.attach(base.with(KEY, "closed elsewhere")));
          scopeManager.swap(original);
        });
    // closed while the shared stack isn't swapped in, leaving it closed on top of that stack
    scope.get().close();
    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          assertSame(base, scopeManager.currentContext());
          assertEquals(1, scopeManager.scopeStack().depth());
          scopeManager.swap(original);
        });
  }

  @Test
  void sharedContextReadsTheLiveTopAndDerivesIndependentContexts() throws Exception {
    ContextKey<String> extra = ContextKey.named("shared-context-extra");
    Context shared = scopeManager.sharedContextStack(base);
    Context before = shared.with(extra, "before");
    AtomicReference<ContextScope> opened = new AtomicReference<>();

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          opened.set(scopeManager.attach(base.with(KEY, "opened")));
          scopeManager.swap(original);
        });

    assertEquals("opened", shared.get(KEY));
    assertNull(shared.get(extra));
    assertEquals("base", before.get(KEY));
    assertEquals("before", before.get(extra));
    Context after = shared.with(extra, "after");
    assertEquals("opened", after.get(KEY));
    assertEquals("after", after.get(extra));

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          opened.get().close();
          scopeManager.swap(original);
        });

    assertEquals("base", shared.get(KEY));
    assertNull(shared.get(extra));
    assertEquals("opened", after.get(KEY));
  }

  @Test
  void overdueSharedRootIsRemovedBeforeAnotherWorkerRuns() throws Exception {
    Context shared = scopeManager.sharedContextStack(Context.root());
    AgentSpan iteration = tracer.buildSpan("test", "iteration").start();
    AtomicInteger closes = new AtomicInteger();
    scopeManager.addScopeListener(
        new ScopeListener() {
          @Override
          public void afterScopeActivated() {}

          @Override
          public void afterScopeClosed() {
            closes.incrementAndGet();
          }
        });

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          ScopeStack stack = scopeManager.scopeStack();
          ContinuableScope root =
              new ContinuableScope(
                  scopeManager, iteration, ContinuableScope.ITERATION, true, Stateful.DEFAULT);
          stack.push(root);
          assertSame(iteration, scopeManager.activeSpan());
          // simulate the cleaner marking an expired root, without scheduling its background timer
          stack.overdueRootScope = root;
          iteration.finishWithEndToEnd();
          assertNull(scopeManager.active());
          stack.cleanup();
          assertNull(stack.overdueRootScope);
          scopeManager.swap(original);
        });

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          assertNull(scopeManager.scopeStack().top());
          assertNull(scopeManager.active());
          assertEquals(0, scopeManager.scopeStack().depth());
          assertSame(Context.root(), scopeManager.currentContext());
          scopeManager.swap(original);
        });
    assertEquals(1, closes.get());
  }

  @TableTest({
    "Scenario    | Parent present",
    "Active only | false         ",
    "With parent | true          "
  })
  void savedContextRestoresAnIndependentStackOnAnotherThread(boolean parentPresent)
      throws Exception {
    ContextScope parent = parentPresent ? scopeManager.attach(base) : null;
    Context activeContext = base.with(KEY, "active");
    ContextScope active = scopeManager.attach(activeContext);
    ScopeStack source = scopeManager.scopeStack();
    Context saved = scopeManager.swap(Context.root());

    try {
      runOnNewThread(
          () -> {
            Context original = scopeManager.swap(saved);
            try {
              ScopeStack restored = scopeManager.scopeStack();
              assertNotSame(source, restored);
              assertSame(active, restored.top());
              assertSame(parent, restored.parent());
              assertEquals(parentPresent ? 2 : 1, restored.depth());
              assertSame(activeContext, scopeManager.currentContext());
              try (ContextScope child = scopeManager.attach(base.with(KEY, "worker"))) {
                assertEquals("worker", scopeManager.currentContext().get(KEY));
                assertSame(active, source.top());
                assertEquals(parentPresent ? 2 : 1, source.depth());
              }
              assertSame(activeContext, scopeManager.currentContext());
            } finally {
              scopeManager.swap(original);
            }
          });
    } finally {
      scopeManager.swap(saved);
      active.close();
      if (parent != null) {
        parent.close();
      }
    }
  }

  @Test
  void threadsCanUseTheSharedStackAtTheSameTime() throws Exception {
    Context shared = scopeManager.sharedContextStack(base);
    int iterations = 10_000;
    CyclicBarrier start = new CyclicBarrier(2);
    Runnable attachAndClose =
        () -> {
          Context original = scopeManager.swap(shared);
          try {
            start.await(10, SECONDS);
            for (int i = 0; i < iterations; i++) {
              scopeManager.attach(base.with(KEY, Thread.currentThread().getName() + i)).close();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            scopeManager.swap(original);
          }
        };
    Thread first = new Thread(attachAndClose, "first");
    Thread second = new Thread(attachAndClose, "second");
    runThreads(first, second);

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          assertSame(base, scopeManager.currentContext());
          assertEquals(1, scopeManager.scopeStack().depth());
          scopeManager.swap(original);
        });
  }

  @Test
  void threadsCanReuseTheSameScopeAtTheSameTime() throws Exception {
    Context shared = scopeManager.sharedContextStack(base);
    int iterations = 100_000;
    CyclicBarrier start = new CyclicBarrier(2);
    Runnable reattachBase =
        () -> {
          Context original = scopeManager.swap(shared);
          try {
            start.await(10, SECONDS);
            for (int i = 0; i < iterations; i++) {
              // reuses the seed scope, adding and removing a reference each time
              scopeManager.attach(base).close();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            scopeManager.swap(original);
          }
        };
    Thread first = new Thread(reattachBase, "first");
    Thread second = new Thread(reattachBase, "second");
    runThreads(first, second);

    assertSeedHoldsOnlyItsOriginalReference(shared);
  }

  @Test
  void scopeCanBeClosedFromAnotherStackWhileItsReused() throws Exception {
    Context shared = scopeManager.sharedContextStack(base);
    int references = 20_000;
    List<ContextScope> extraReferences = new ArrayList<>();
    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          for (int i = 0; i < references; i++) {
            extraReferences.add(scopeManager.attach(base));
          }
          scopeManager.swap(original);
        });
    CyclicBarrier start = new CyclicBarrier(2);
    Thread reuser =
        new Thread(
            () -> {
              Context original = scopeManager.swap(shared);
              try {
                start.await(10, SECONDS);
                for (int i = 0; i < references; i++) {
                  scopeManager.attach(base).close();
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              } finally {
                scopeManager.swap(original);
              }
            });
    // closes the extra references on its own stack, while the other thread reuses the same scope
    Thread closer =
        new Thread(
            () -> {
              try {
                start.await(10, SECONDS);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              extraReferences.forEach(ContextScope::close);
            });
    runThreads(reuser, closer);

    assertSeedHoldsOnlyItsOriginalReference(shared);
  }

  @Test
  void clearedIterationScopeIsNotReusedByAnotherThread() throws Exception {
    int rounds = 2_000;
    CyclicBarrier phase = new CyclicBarrier(2);
    AtomicReference<Context> shared = new AtomicReference<>();
    AtomicReference<AgentSpan> iterationSpan = new AtomicReference<>();
    AtomicReference<ContinuableScope> iterationScope = new AtomicReference<>();
    Runnable reuser =
        () -> {
          try {
            for (int round = 0; round < rounds; round++) {
              phase.await(10, SECONDS); // iteration scope is on top
              Context original = scopeManager.swap(shared.get());
              for (int i = 0; i < 200; i++) {
                // reuses the iteration scope until it's cleared, then pushes a new scope instead
                scopeManager.activateSpan(iterationSpan.get()).close();
              }
              scopeManager.swap(original);
              phase.await(10, SECONDS); // round finished
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    Runnable closer =
        () -> {
          try {
            for (int round = 0; round < rounds; round++) {
              shared.set(scopeManager.sharedContextStack(base));
              Context original = scopeManager.swap(shared.get());
              ContextScope outer = scopeManager.attach(base.with(KEY, "outer"));
              iterationSpan.set(tracer.buildSpan("test", "iteration").start());
              iterationScope.set((ContinuableScope) scopeManager.activateNext(iterationSpan.get()));
              phase.await(10, SECONDS);
              // closing the outer scope clears the overdue iteration scope above it
              outer.close();
              scopeManager.swap(original);
              phase.await(10, SECONDS);
              assertFalse(iterationScope.get().alive(), "cleared scope retained in round " + round);
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
    Thread first = new Thread(reuser, "reuser");
    Thread second = new Thread(closer, "closer");
    runThreads(first, second);
  }

  @Test
  void threadGetsItsOwnStackBackAfterNestedSwaps() {
    Context outer = scopeManager.sharedContextStack(base);
    Context inner = scopeManager.sharedContextStack(base.with(KEY, "inner"));
    ScopeStack threadStack = scopeManager.scopeStack();

    Context original = scopeManager.swap(outer);
    ScopeStack outerStack = scopeManager.scopeStack();
    Context outerToken = scopeManager.swap(inner);
    scopeManager.swap(outerToken);
    assertSame(outerStack, scopeManager.scopeStack());
    scopeManager.swap(original);

    assertSame(threadStack, scopeManager.scopeStack());
  }

  @Test
  void threadReusesOneTokenForItsOwnStack() throws Exception {
    Context shared = scopeManager.sharedContextStack(base);
    Context opened = base.with(KEY, "thread");
    AtomicReference<Context> token = new AtomicReference<>();

    runOnNewThread(
        () -> {
          ContextScope scope = scopeManager.attach(opened);
          Context first = scopeManager.swap(shared);
          scopeManager.swap(first);
          Context second = scopeManager.swap(shared);
          assertSame(first, second);
          scopeManager.swap(second);
          assertSame(opened, scopeManager.currentContext());
          token.set(second);
          scope.close();
        });
    runOnNewThread(
        () -> {
          // the token only restores its own thread's stack; other threads get an empty stack
          scopeManager.swap(token.get());
          assertNull(scopeManager.active());
        });
  }

  @Test
  void reusedThreadTokenReadsItsOwnLiveStack() {
    ContextKey<String> extra = ContextKey.named("thread-token-extra");
    Context shared = scopeManager.sharedContextStack(base.with(KEY, "coroutine"));
    ContextScope parentScope = scopeManager.attach(base);
    Context token = scopeManager.swap(shared);

    assertEquals("coroutine", scopeManager.currentContext().get(KEY));
    assertEquals("base", token.get(KEY));
    Context captured = token.with(extra, "captured");
    assertEquals("base", captured.get(KEY));
    assertEquals("captured", captured.get(extra));
    assertNull(token.get(extra));

    scopeManager.swap(token);
    ContextScope childScope = scopeManager.attach(base.with(KEY, "child"));
    Context reused = scopeManager.swap(shared);
    assertSame(token, reused);
    assertEquals("child", token.get(KEY));
    assertEquals("base", captured.get(KEY));

    scopeManager.swap(reused);
    childScope.close();
    parentScope.close();
    assertNull(token.get(KEY));
  }

  @Test
  void seedingFromSharedContextStartsFromItsCurrentTop() throws Exception {
    Context parent = scopeManager.sharedContextStack(base);
    Context opened = base.with(KEY, "opened");
    AtomicReference<Context> child = new AtomicReference<>();

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(parent);
          scopeManager.attach(opened);
          child.set(scopeManager.sharedContextStack(parent));
          scopeManager.swap(original);
        });
    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(child.get());
          assertSame(opened, scopeManager.currentContext());
          // the child has its own stack
          scopeManager.attach(base.with(KEY, "child"));
          scopeManager.swap(original);
          scopeManager.swap(parent);
          assertSame(opened, scopeManager.currentContext());
          scopeManager.swap(original);
        });
  }

  @Test
  void listenersSeeTheScopeBeingActivated() throws Exception {
    Context shared = scopeManager.sharedContextStack(Context.root());
    AgentSpan parent = tracer.buildSpan("test", "parent").start();
    AgentSpan child = tracer.buildSpan("test", "child").start();
    List<AgentSpan> activeSpans = new ArrayList<>();
    scopeManager.addScopeListener(
        new ScopeListener() {
          @Override
          public void afterScopeActivated() {
            activeSpans.add(scopeManager.activeSpan());
          }

          @Override
          public void afterScopeClosed() {}
        });

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          ContextScope parentScope = scopeManager.activateSpan(parent);
          scopeManager.activateSpan(child).close();
          parentScope.close();
          scopeManager.swap(original);
        });

    assertEquals(3, activeSpans.size());
    assertSame(parent, activeSpans.get(0));
    assertSame(child, activeSpans.get(1));
    assertSame(parent, activeSpans.get(2), "parent reactivated");
  }

  private void assertSeedHoldsOnlyItsOriginalReference(Context shared) throws Exception {
    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          assertSame(base, scopeManager.currentContext());
          assertEquals(1, scopeManager.scopeStack().depth());
          ContinuableScope seed = scopeManager.scopeStack().top();
          assertTrue(seed.alive());
          // releasing the original reference closes it, so there are no extra references left
          assertFalse(seed.release());
          scopeManager.swap(original);
        });
  }

  private static void runOnNewThread(Runnable task) throws Exception {
    runThreads(new Thread(task, "worker"));
  }

  /** Stops peers on failure and bounds shutdown, including when a worker cannot be interrupted. */
  private static void runThreads(Thread... threads) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    for (Thread thread : threads) {
      // a monitor deadlock cannot be interrupted; it must not prevent the test JVM exiting
      thread.setDaemon(true);
      thread.setUncaughtExceptionHandler(
          (failed, error) -> {
            failure.compareAndSet(null, error);
            for (Thread peer : threads) {
              if (peer != failed) {
                peer.interrupt();
              }
            }
          });
    }
    try {
      for (Thread thread : threads) {
        thread.start();
      }
      for (Thread thread : threads) {
        thread.join(10_000);
      }
    } finally {
      for (Thread thread : threads) {
        thread.interrupt();
      }
      for (Thread thread : threads) {
        thread.join(10_000);
      }
    }
    if (failure.get() instanceof Error) {
      throw (Error) failure.get();
    } else if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
    for (Thread thread : threads) {
      assertFalse(thread.isAlive(), thread.getName() + " did not terminate");
    }
  }
}
