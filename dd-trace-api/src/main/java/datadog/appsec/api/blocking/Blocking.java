package datadog.appsec.api.blocking;

import java.util.Collections;
import java.util.Map;

/**
 * Functionality related to blocking requests. This functionality is available if and only if the
 * AppSec subsystem is enabled.
 */
public class Blocking {
  private static volatile BlockingService SERVICE = BlockingService.NOOP;

  private Blocking() {}

  /**
   * Starts a user-blocking action.
   *
   * @param userId a non-null unique identifier for the currently identified user
   * @return an object through which the action in case of a match can be controlled
   */
  public static UserBlockingSpec forUser(String userId) {
    if (userId == null) {
      throw new NullPointerException("userId cannot be null");
    }
    if (SERVICE == null) {
      throw new IllegalStateException("Blocking service is not available. Is AppSec disabled?");
    }
    return new UserBlockingSpec(userId);
  }

  /**
   * Tries to commit an HTTP response with the specified status code and content type. No exception
   * should escape if this fails.
   *
   * <p>This method returns <code>false</code> if blocking cannot be attempted because of the
   * following conditions:
   *
   * <ul>
   *   <li>there is no span available,
   *   <li>blocking is not supported for the server being used, or
   *   <li>the blocking service is otherwise incapable, in principle, of blocking (e.g. it's a no-op
   *       implementation).
   * </ul>
   *
   * <p>This method does not return false if a blocking response has already been committed, or if
   * there is an error when trying to write the response. This is because the writing may happen
   * asynchronously, after the caller has returned.
   *
   * @param statusCode the status code of the response
   * @param contentType the content-type of the response.
   * @param extraHeaders map of headers to set in the response
   * @return whether blocking was/will be attempted
   */
  public static boolean tryCommitBlockingResponse(
      int statusCode, BlockingContentType contentType, Map<String, String> extraHeaders) {
    try {
      return SERVICE.tryCommitBlockingResponse(statusCode, contentType, extraHeaders);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Equivalent to calling {@link #tryCommitBlockingResponse(int, BlockingContentType, Map<String,
   * String>)} with the last parameter being an empty map.
   *
   * @param statusCode the status code of the response
   * @param contentType the content-type of the response.
   * @return whether blocking was/will be attempted
   */
  public static boolean tryCommitBlockingResponse(int statusCode, BlockingContentType contentType) {
    try {
      boolean committedBlockingResponse =
          SERVICE.tryCommitBlockingResponse(
              statusCode, BlockingContentType.NONE, Collections.emptyMap());
      return committedBlockingResponse;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Controls the implementation for blocking. The AppSec subsystem calls this method on startup.
   * This can be called explicitly for e.g. testing purposes.
   *
   * @param service the implementation for blocking.
   */
  public static void setBlockingService(BlockingService service) {
    Blocking.SERVICE = service;
  }

  public static class UserBlockingSpec {
    private final String userId;

    private UserBlockingSpec(String userId) {
      this.userId = userId;
    }

    /**
     * Whether the user in question should be blocked, and, if so, the details of the blocking
     * response
     *
     * @return the details of the blocking response, or null if the user is not to be blocked
     */
    public BlockingDetails shouldBlock() {
      return SERVICE.shouldBlockUser(userId);
    }

    /**
     * Convenience method that:
     *
     * <ul>
     *   <li>calls {@link #shouldBlock()}, and, if the result is non-null
     *   <li>calls {@link #tryCommitBlockingResponse(int, BlockingContentType)}, and
     *   <li>throws an exception of the type {@link BlockingException}
     * </ul>
     */
    public void blockIfMatch() {
      BlockingDetails blockingDetails = shouldBlock();
      if (blockingDetails == null) {
        return;
      }

      SERVICE.tryCommitBlockingResponse(
          blockingDetails.statusCode,
          blockingDetails.blockingContentType,
          blockingDetails.extraHeaders);
      throw new BlockingException("Blocking user with id '" + userId + "'");
    }
  }
}
