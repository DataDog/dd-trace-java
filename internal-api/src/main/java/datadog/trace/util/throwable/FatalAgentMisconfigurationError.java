package datadog.trace.util.throwable;

/**
 * This error represents a critical agent configuration issue that needs to be signalled to the
 * client in a fail-fast manner. It is reserved for the rare cases when we want to abort the entire
 * process rather than proceeding to start without the properly initialised agent.
 *
 * <p>This class is referred to by name in some parts of the code, so please be cautious when
 * renaming or moving it.
 */
public class FatalAgentMisconfigurationError extends Error {
  public FatalAgentMisconfigurationError(String message) {
    super(message);
  }
}
