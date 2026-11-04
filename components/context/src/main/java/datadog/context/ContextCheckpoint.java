package datadog.context;

import javax.annotation.Nullable;

/** Checkpoints a context with execution-unit data in anticipation of a context swap. */
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
