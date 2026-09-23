package datadog.trace.agent.tooling.bytebuddy.profiling;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import net.bytebuddy.agent.builder.AgentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link UnwrappingVisitor} redefines the structure of a class by adding an interface, meaning that
 * it cannot be applied to already loaded classes.
 *
 * <p>This listener will disable the visitor to prevent a failure with the whole redefinition batch.
 */
public final class TaskWrapperRedefinitionStrategyListener
    extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TaskWrapperRedefinitionStrategyListener.class);
  private static final boolean DEBUG = LOGGER.isDebugEnabled();

  public static final TaskWrapperRedefinitionStrategyListener INSTANCE =
      new TaskWrapperRedefinitionStrategyListener();

  private TaskWrapperRedefinitionStrategyListener() {}

  @Override
  @Nonnull
  public Iterable<? extends List<Class<?>>> onError(
      final int index,
      @Nonnull final List<Class<?>> batch,
      @Nonnull final Throwable throwable,
      @Nonnull final List<Class<?>> types) {
    if (UnwrappingVisitor.ENABLED) {
      // logged once: the flag is one-way, so this branch cannot be re-entered
      LOGGER.info(
          "Disabling task unwrapping for queueing time profiling: retransformation failed for a"
              + " batch of {} classes because adding the TaskWrapper interface is a structural"
              + " change the JVM rejects for already loaded classes (e.g. materialized from a"
              + " JDK 24+ AOT cache). Retrying the batch without it. Queueing time is still"
              + " recorded, but the task type is reported as the wrapper class. All other"
              + " instrumentation is preserved.",
          batch.size());
      UnwrappingVisitor.ENABLED = false;
      return Collections.singletonList(batch);
    } else {
      if (DEBUG) {
        LOGGER.debug(
            "Exception while retransforming after disabling the visitor in batch {}, task unwrapping is disabled",
            index);
      }
      return Collections.emptyList();
    }
  }

  @Override
  public void onComplete(
      final int amount, final List<Class<?>> types, final Map<List<Class<?>>, Throwable> failures) {
    if (DEBUG) {
      if (!UnwrappingVisitor.ENABLED) {
        LOGGER.debug("Retransforming succeeded with a disabled visitor");
      }
    }
  }
}
