package datadog.context;

import de.thetaphi.forbiddenapis.SuppressForbidden;

/** Utility class to register the {@link ContextScope} and {@link ContextBinder} implementations. */
public final class ContextProvider {
  static volatile ContextBinder contextBinder;
  static volatile ContextStorage contextStorage;
  private static final String DEFAULT_CONTEXT_STORAGE_CLASS_NAME =
      "datadog.context.DefaultContextStorage";

  static ContextBinder contextBinder() {
    if (contextBinder == null) {
      throw new IllegalStateException("No context binder registered");
    }
    return contextBinder;
  }

  /**
   * Registers the {@link ContextBinder} implementation. Only the first implementation will be
   * registered.
   *
   * @param contextBinder The {@link ContextBinder} implementation to register.
   */
  public static void registerContextBinder(ContextBinder contextBinder) {
    if (ContextProvider.contextBinder == null && contextBinder != null) {
      ContextProvider.contextBinder = contextBinder;
    }
  }

  @SuppressForbidden
  static ContextStorage contextStorage() {
    if (contextStorage == null) {
      try {
        contextStorage =
            (ContextStorage) Class.forName(DEFAULT_CONTEXT_STORAGE_CLASS_NAME).newInstance();
      } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
        throw new RuntimeException("Failed to instantiate default context storage", e);
      }
    }
    return contextStorage;
  }

  /**
   * Registers the {@link ContextStorage} implementation. Only the first implementation will be
   * registered.
   *
   * @param contextStorage The {@link ContextStorage} implementation to register.
   */
  public static void registerContextStorage(ContextStorage contextStorage) {
    if (ContextProvider.contextStorage == null && contextStorage != null) {
      ContextProvider.contextStorage = contextStorage;
    }
  }
}
