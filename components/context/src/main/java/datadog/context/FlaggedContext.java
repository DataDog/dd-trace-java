package datadog.context;

import javax.annotation.Nullable;

/** Saves non-zero execution-unit flags for a later swap to a different underlying context. */
final class FlaggedContext implements ContextHandle {
  final Context context;
  final byte flags;

  FlaggedContext(Context context, byte flags) {
    this.context = context;
    this.flags = flags;
  }

  @Override
  @Nullable
  public <T> T get(ContextKey<T> key) {
    return context.get(key);
  }
}
