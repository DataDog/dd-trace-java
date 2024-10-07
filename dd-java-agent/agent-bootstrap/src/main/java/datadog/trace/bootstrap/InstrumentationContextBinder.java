package datadog.trace.bootstrap;

import static java.util.Objects.requireNonNull;

import datadog.context.Context;
import datadog.context.ContextBinder;
import datadog.context.ContextProvider;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Implements a {@link ContextBinder} over the {@link FieldBackedContextStore}. */
public class InstrumentationContextBinder implements ContextBinder {
  private static final String CONTEXT_CLASS_NAME = Context.class.getName();

  /** Registers the {@link ContextBinder} implementation. */
  public static void register() {
    ContextProvider.registerContextBinder(new InstrumentationContextBinder());
  }

  @Override
  public void attach(@Nonnull Context context, @Nonnull Object carrier) {
    requireNonNull(carrier, "Context carrier cannot be null");
    getContextStore(carrier).put(carrier, context);
  }

  @Nullable
  @Override
  public Context retrieveFrom(@Nonnull Object carrier) {
    requireNonNull(carrier, "Context carrier cannot be null");
    return (Context) getContextStore(carrier).get(carrier);
  }

  private ContextStore<Object, Object> getContextStore(Object carrier) {
    String carrierClassName = carrier.getClass().getName();
    int contextStoreId =
        FieldBackedContextStores.getContextStoreId(carrierClassName, CONTEXT_CLASS_NAME);
    return FieldBackedContextStores.getContextStore(contextStoreId);
  }
}
