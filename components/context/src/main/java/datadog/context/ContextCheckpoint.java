package datadog.context;

import javax.annotation.Nullable;

/** Stores a context and flags for a later swap. */
final class ContextCheckpoint implements ContextHandle {
  final Context context;
  final byte flags;

  ContextCheckpoint(Context context, byte flags) {
    this.context = context;
    this.flags = flags;
  }

  @Override
  @Nullable
  public <T> T get(ContextKey<T> key) {
    return context.get(key);
  }
}
