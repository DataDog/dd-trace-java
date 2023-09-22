package datadog.trace.agent.tooling.bytebuddy.iast;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import net.bytebuddy.agent.builder.AgentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TaintableVisitor} redefines the structure of a class by adding interfaces and fields,
 * meaning that it cannot be applied to already loaded classes.
 *
 * <p>This listener will disable the visitor to prevent a failure with the whole redefinition batch.
 */
public final class TaintableRedefinitionStrategyListener
    extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(TaintableRedefinitionStrategyListener.class);
  private static final boolean DEBUG = LOGGER.isDebugEnabled();

  public static final TaintableRedefinitionStrategyListener INSTANCE =
      new TaintableRedefinitionStrategyListener();

  private TaintableRedefinitionStrategyListener() {}

  @Override
  @Nonnull
  public Iterable<? extends List<Class<?>>> onError(
      final int index,
      @Nonnull final List<Class<?>> batch,
      @Nonnull final Throwable throwable,
      @Nonnull final List<Class<?>> types) {
    if (TaintableVisitor.ENABLED) {
      if (DEBUG) {
        LOGGER.debug(
            "Exception while retransforming with the visitor in batch {}, disabling it", index);
      }
      TaintableVisitor.ENABLED = false;
      return Collections.singletonList(batch);
    } else {
      if (DEBUG) {
        LOGGER.debug(
            "Exception while retransforming after disabling the visitor in batch {}, classes won't be instrumented",
            index);
      }
      return Collections.emptyList();
    }
  }

  @Override
  public void onComplete(
      final int amount, final List<Class<?>> types, final Map<List<Class<?>>, Throwable> failures) {
    if (DEBUG) {
      if (!TaintableVisitor.ENABLED) {
        LOGGER.debug("Retransforming succeeded with a disabled visitor");
      }
    }
  }
}
