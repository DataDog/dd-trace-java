package datadog.trace.core.scopemanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.ScopeManagerTestBridge;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
  void threadsCanUseTheSharedStackAtTheSameTime() throws Exception {
    Context shared = scopeManager.sharedContextStack(base);
    int iterations = 10_000;
    CyclicBarrier start = new CyclicBarrier(2);
    Runnable attachAndClose =
        () -> {
          Context original = scopeManager.swap(shared);
          try {
            start.await();
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
    AtomicReference<Throwable> failure = new AtomicReference<>();
    first.setUncaughtExceptionHandler((t, e) -> failure.set(e));
    second.setUncaughtExceptionHandler((t, e) -> failure.set(e));
    first.start();
    second.start();
    first.join();
    second.join();
    assertNull(failure.get());

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
            start.await();
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
    AtomicReference<Throwable> failure = new AtomicReference<>();
    first.setUncaughtExceptionHandler((t, e) -> failure.set(e));
    second.setUncaughtExceptionHandler((t, e) -> failure.set(e));
    first.start();
    second.start();
    first.join();
    second.join();
    assertNull(failure.get());

    runOnNewThread(
        () -> {
          Context original = scopeManager.swap(shared);
          assertSame(base, scopeManager.currentContext());
          assertEquals(1, scopeManager.scopeStack().depth());
          // the seed scope still holds exactly its original reference
          ContinuableScope seed = scopeManager.scopeStack().top();
          assertTrue(seed.alive());
          assertFalse(seed.decrementReferences());
          seed.incrementReferences();
          scopeManager.swap(original);
        });
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

  private static void runOnNewThread(Runnable task) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread thread = new Thread(task);
    thread.setUncaughtExceptionHandler((t, e) -> failure.set(e));
    thread.start();
    thread.join();
    if (failure.get() instanceof Error) {
      throw (Error) failure.get();
    } else if (failure.get() != null) {
      throw new AssertionError(failure.get());
    }
  }
}
