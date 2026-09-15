package datadog.trace.agent.tooling.bytebuddy.profiling;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * {@link UnwrappingVisitor} redefines the structure of a class by adding an interface, meaning that
 * it cannot be applied to already loaded classes.
 *
 * <p>This listener disables the visitor and retries the batch, so that unretransformable classes do
 * not abort the whole instrumentation install.
 */
public final class TaskWrapperRedefinitionStrategyListener
    extends AgentBuilder.RedefinitionStrategy.Listener.Adapter {

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
      UnwrappingVisitor.ENABLED = false;
      return Collections.singletonList(batch);
    }
    return Collections.emptyList();
  }
}
