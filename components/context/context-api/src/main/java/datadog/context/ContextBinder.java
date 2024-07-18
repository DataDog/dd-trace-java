package datadog.context;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The {@link ContextBinder} is in charge of attaching and retrieving {@link Context} to carrier
 * objects.
 */
public interface ContextBinder {
  @Nonnull
  static ContextBinder get() {
    return ContextProvider.contextBinder();
  }

  void attach(@Nonnull Context context, @Nonnull Object carrier);

  @Nullable
  Context retrieveFrom(@Nonnull Object carrier);
}
