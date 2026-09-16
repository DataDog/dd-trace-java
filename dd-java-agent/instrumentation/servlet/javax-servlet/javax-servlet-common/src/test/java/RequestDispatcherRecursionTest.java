import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the SAP Hybris/Spartacus StackOverflow reported in v1.63.0.
 *
 * <p>Root cause: {@code RequestDispatcherAdvice.start()} calls {@code injectContext()} which calls
 * {@code request.setAttribute()} for each propagation header. In some request wrappers (Hybris
 * internals, SAP Commerce), {@code setAttribute()} internally triggers another {@code
 * RequestDispatcher.forward()} call. Without a re-entrancy guard the advice recurses until {@code
 * StackOverflowError}, visible in production as many "Failed to handle exception in instrumentation
 * for ApplicationDispatcher" log entries.
 *
 * <p>The fix is a {@code CallDepthThreadLocalMap} guard in {@code RequestDispatcherAdvice.start()}
 * analogous to the one already present in {@code AsyncContextInstrumentation}.
 */
class RequestDispatcherRecursionTest extends AbstractInstrumentationTest {

  /** Minimal {@code RequestDispatcher} stub — instrumented by ByteBuddy via interface match. */
  static class TestDispatcher implements RequestDispatcher {
    @Override
    public void forward(ServletRequest req, ServletResponse resp)
        throws ServletException, IOException {}

    @Override
    public void include(ServletRequest req, ServletResponse resp)
        throws ServletException, IOException {}
  }

  /**
   * {@code RequestDispatcher} with a non-trivial body simulating {@code ApplicationDispatcher}
   * (Tomcat / SAP Commerce), which traverses a filter and valve chain adding significant call
   * depth.
   */
  static class RealisticDispatcher implements RequestDispatcher {
    @Override
    public void forward(ServletRequest req, ServletResponse resp)
        throws ServletException, IOException {
      simulateFilterChain(200);
    }

    @Override
    public void include(ServletRequest req, ServletResponse resp)
        throws ServletException, IOException {}

    private static void simulateFilterChain(int depth) {
      if (depth > 0) simulateFilterChain(depth - 1);
    }
  }

  /** Creates a proxy stub for {@code iface} where all methods return null / 0 / false. */
  @SuppressWarnings("unchecked")
  static <T> T nullStub(Class<T> iface) {
    return (T)
        Proxy.newProxyInstance(
            iface.getClassLoader(),
            new Class<?>[] {iface},
            (proxy, method, args) -> {
              Class<?> ret = method.getReturnType();
              if (ret == boolean.class) return false;
              if (ret == int.class || ret == long.class) return 0;
              return null;
            });
  }

  @Test
  void forward_doesNotRecurse_whenSetAttributeTriggersRedispatch() throws Exception {
    TestDispatcher dispatcher = new TestDispatcher();
    HttpServletResponse response = nullStub(HttpServletResponse.class);

    AtomicInteger currentDepth = new AtomicInteger(0);
    AtomicInteger maxDepth = new AtomicInteger(0);

    // Hybris-style wrapper: setAttribute() re-triggers a dispatch. Capped at depth 3
    // to bound total calls (≈ N^3, N ≈ 5 headers) and avoid OOM without the fix.
    final int MAX_SETATTRIBUTE_DEPTH = 3;
    HttpServletRequest recursiveRequest =
        new HttpServletRequestWrapper(nullStub(HttpServletRequest.class)) {
          @Override
          public void setAttribute(String name, Object value) {
            int depth = currentDepth.incrementAndGet();
            maxDepth.updateAndGet(d -> Math.max(d, depth));
            try {
              super.setAttribute(name, value);
              if (depth < MAX_SETATTRIBUTE_DEPTH) {
                dispatcher.forward(this, response);
              }
            } catch (Exception | StackOverflowError ignored) {
            } finally {
              currentDepth.decrementAndGet();
            }
          }
        };

    // runUnderTrace provides an active span; without one the advice exits before injectContext().
    runUnderTrace(
        "test",
        () -> {
          dispatcher.forward(recursiveRequest, response);
          return null;
        });

    assertTrue(
        maxDepth.get() >= 1,
        "advice did not call setAttribute() — check that TestDispatcher is instrumented"
            + " by ByteBuddy and that runUnderTrace creates an active span");

    assertEquals(
        1,
        maxDepth.get(),
        "setAttribute() was called recursively to depth "
            + maxDepth.get()
            + ". RequestDispatcherAdvice.start() needs a CallDepthThreadLocalMap guard "
            + "(see AsyncContextInstrumentation for the pattern).");
  }

  /**
   * Regression test for the production SOE scenario with a realistic dispatcher body.
   *
   * <p>Without the fix, the recursive {@code setAttribute()} pattern in Hybris would fill the call
   * stack, and the extra frames from {@code simulateFilterChain()} would tip it into a {@code
   * StackOverflowError} from the method <em>body</em> — captured by {@code @Advice.Thrown} → {@code
   * DECORATE.onError()} → {@code span.setTag("error.stack")}.
   *
   * <p>With the {@code CallDepthThreadLocalMap} fix, re-entrant {@code forward()} calls return
   * immediately from the enter advice (depth &gt; 0), so the stack never saturates and no SOE is
   * thrown. The test verifies exactly this: {@code capturedSoe} must be {@code null}.
   */
  @Test
  void forward_noSoe_withRealisticDispatcherBodyAndSetAttributeRecursion() throws Exception {
    AtomicReference<StackOverflowError> capturedSoe = new AtomicReference<>();
    AtomicBoolean soeSeen = new AtomicBoolean(false); // stops re-dispatching once SOE is caught
    AtomicBoolean setAttributeInvoked = new AtomicBoolean(false); // guards against false-positive

    RealisticDispatcher dispatcher = new RealisticDispatcher();
    HttpServletResponse response = nullStub(HttpServletResponse.class);

    // Hybris-style wrapper: setAttribute() triggers a new forward(); no depth cap.
    HttpServletRequest recursiveRequest =
        new HttpServletRequestWrapper(nullStub(HttpServletRequest.class)) {
          @Override
          public void setAttribute(String name, Object value) {
            super.setAttribute(name, value);
            setAttributeInvoked.set(true);
            if (soeSeen.get()) return; // already captured — stop recursing
            try {
              dispatcher.forward(this, response);
            } catch (StackOverflowError soe) {
              capturedSoe.compareAndSet(null, soe);
              soeSeen.set(true);
              throw soe;
            } catch (Exception ignored) {
            }
          }
        };

    try {
      runUnderTrace(
          "test",
          () -> {
            dispatcher.forward(recursiveRequest, response);
            return null;
          });
    } catch (StackOverflowError ignored) {
      // SOE may reach this level if it is not fully absorbed at inner levels.
    }

    assertTrue(
        setAttributeInvoked.get(),
        "advice did not call setAttribute() — check that RealisticDispatcher is instrumented"
            + " by ByteBuddy and that runUnderTrace creates an active span");

    assertNull(
        capturedSoe.get(),
        "StackOverflowError was thrown — fix regression: CallDepthThreadLocalMap guard "
            + "was removed from RequestDispatcherAdvice.start()");
  }
}
