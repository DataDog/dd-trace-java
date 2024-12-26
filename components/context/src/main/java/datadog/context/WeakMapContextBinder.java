package datadog.context;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** {@link ContextBinder} that uses a global weak map of carriers to contexts. */
final class WeakMapContextBinder implements ContextBinder {

  private static final Map<Object, Context> TRACKED =
      Collections.synchronizedMap(new WeakHashMap<>());

  @Override
  public Context from(Object carrier) {
    Context bound = TRACKED.get(carrier);
    return null != bound ? bound : Context.root();
  }

  @Override
  public void attachTo(Object carrier, Context context) {
    TRACKED.put(carrier, context);
  }

  @Override
  public Context detachFrom(Object carrier) {
    Context previous = TRACKED.remove(carrier);
    return null != previous ? previous : Context.root();
  }
}
