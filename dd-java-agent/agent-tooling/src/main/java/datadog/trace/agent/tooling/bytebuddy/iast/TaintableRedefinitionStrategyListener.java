package datadog.trace.agent.tooling.bytebuddy.iast;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * {@link TaintableVisitor} redefines the structure of a class by adding interfaces and fields,
 * meaning that it cannot be applied to already loaded classes.
 *
 * <p>This listener will disable the visitor to prevent a failure with the whole redefinition batch.
 */
public final class TaintableRedefinitionStrategyListener
    extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

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
      TaintableVisitor.ENABLED = false;
      return Collections.singletonList(batch);
    } else {
      return Collections.emptyList();
    }
  }
}
