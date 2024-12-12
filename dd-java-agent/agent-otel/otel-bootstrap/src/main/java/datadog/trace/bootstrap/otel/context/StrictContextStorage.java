package datadog.trace.bootstrap.otel.context;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;

/**
 * Replaces original class with a simple wrapper to avoid missing reference issue on native-image.
 *
 * <p>The original class is only used for testing purposes when a particular property is set, but
 * native-image follows the reference in {@code LazyStorage} and attempts to load everything it
 * touches, including some types we are not embedding. This simple replacement fixes this issue.
 */
final class StrictContextStorage implements ContextStorage {
  private final ContextStorage delegate;

  static StrictContextStorage create(ContextStorage delegate) {
    return new StrictContextStorage(delegate);
  }

  public StrictContextStorage(ContextStorage delegate) {
    this.delegate = delegate;
  }

  @Override
  public Scope attach(Context context) {
    return delegate.attach(context);
  }

  @Override
  public Context current() {
    return delegate.current();
  }
}
