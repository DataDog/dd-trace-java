package datadog.context;

import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;

public class DefaultContextBinder implements ContextBinder {
  private final Map<Object, Context> bindings = new WeakHashMap<>();

  /** Registers the {@link ContextBinder} implementation. */
  public static void register() {
    ContextProvider.registerContextBinder(new DefaultContextBinder());
  }

  @Override
  public void attach(@Nonnull Context context, @Nonnull Object carrier) {
    this.bindings.put(carrier, context);
  }

  @Override
  public Context retrieveFrom(@Nonnull Object carrier) {
    return this.bindings.get(carrier);
  }
}
