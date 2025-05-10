package datadog.context;

import static datadog.context.Context.root;
import static java.util.Collections.synchronizedMap;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.ParametersAreNonnullByDefault;

/** {@link ContextBinder} that uses a global weak map of carriers to contexts. */
@ParametersAreNonnullByDefault
final class WeakMapContextBinder implements ContextBinder {
  static final ContextBinder INSTANCE = new WeakMapContextBinder();

  private static final Map<Object, Context> TRACKED = synchronizedMap(new WeakHashMap<>());

  @Override
  public Context from(Object carrier) {
    requireNonNull(carrier, "Context carrier cannot be null");
    Context bound = TRACKED.get(carrier);
    return null != bound ? bound : root();
  }

  @Override
  public void attachTo(Object carrier, Context context) {
    requireNonNull(carrier, "Context carrier cannot be null");
    requireNonNull(context, "Context cannot be null. Use detachFrom() instead.");
    TRACKED.put(carrier, context);
  }

  @Override
  public Context detachFrom(Object carrier) {
    requireNonNull(carrier, "Context key cannot be null");
    Context previous = TRACKED.remove(carrier);
    return null != previous ? previous : root();
  }
}
