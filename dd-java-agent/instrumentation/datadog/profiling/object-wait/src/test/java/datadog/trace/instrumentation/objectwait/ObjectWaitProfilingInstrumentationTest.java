package datadog.trace.instrumentation.objectwait;

import static org.mockito.Mockito.verify;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
import datadog.trace.instrumentation.objectwait.ObjectWaitProfilingInstrumentation.WaitAdvice;
import java.lang.reflect.Constructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link ObjectWaitProfilingInstrumentation}.
 *
 * <p>Detailed interval eligibility is covered by {@code TaskBlockHelperTest}; this class verifies
 * that Object.wait advice delegates to the shared Java-level TaskBlock helper.
 */
@ExtendWith(MockitoExtension.class)
class ObjectWaitProfilingInstrumentationTest {

  private static final long START_TICKS = 42_000_000L;
  private static final long BLOCKER = 1234L;

  @Mock private ProfilingContextIntegration profiling;

  @Test
  void after_nullState_doesNotThrow() {
    WaitAdvice.after(null);
  }

  @Test
  void after_eligibleState_emitsTaskBlockWithZeroUnblockingSpanId() throws Exception {
    TaskBlockHelper.State state = newState(System.nanoTime() - 2 * taskBlockThresholdNanos());

    WaitAdvice.after(state);

    // Span ids are no longer passed across JNI; native side reads them from OTEP TLS.
    verify(profiling).recordTaskBlock(START_TICKS, BLOCKER, 0L);
  }

  private TaskBlockHelper.State newState(long startNanos) throws Exception {
    Constructor<TaskBlockHelper.State> constructor =
        TaskBlockHelper.State.class.getDeclaredConstructor(
            ProfilingContextIntegration.class, long.class, long.class, long.class);
    constructor.setAccessible(true);
    return constructor.newInstance(profiling, START_TICKS, startNanos, BLOCKER);
  }

  private static long taskBlockThresholdNanos() throws Exception {
    java.lang.reflect.Field field = TaskBlockHelper.class.getDeclaredField("MIN_TASK_BLOCK_NANOS");
    field.setAccessible(true);
    return field.getLong(null);
  }
}
