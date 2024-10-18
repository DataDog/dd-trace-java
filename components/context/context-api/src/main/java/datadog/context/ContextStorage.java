package datadog.context;

import javax.annotation.Nonnull;

/**
 * The {@link ContextStorage} is in charge of setting and retrieving <i>current</i> contexts to
 * execution units ({@link Thread}).
 */
public interface ContextStorage {
  @Nonnull
  static ContextStorage get() {
    return ContextProvider.contextStorage();
  }

  /**
   * Creates an empty context instance.
   *
   * @return An empty context instance.
   */
  @Nonnull
  Context empty();

  /**
   * Retrieves the <i>current</i> context attached to the current execution unit ({@link Thread}).
   *
   * @return The <i>current</i> context, {@link #empty()} if no <i>current</i> context.
   */
  @Nonnull
  Context current();

  /**
   * Sets the context as <i>current</i> for the current execution context ({@link Thread}).
   *
   * @param context The context to set as <i>current</i>.
   * @return The scope representing the context current activation.
   */
  @Nonnull
  ContextScope attach(@Nonnull Context context);
}
