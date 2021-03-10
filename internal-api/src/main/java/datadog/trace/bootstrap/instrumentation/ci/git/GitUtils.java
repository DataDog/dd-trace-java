package datadog.trace.bootstrap.instrumentation.ci.git;

import java.net.URI;
import java.net.URISyntaxException;

public class GitUtils {

  /**
   * Normalizes the Git references origin/my-branch -> my-branch refs/heads/my-branch -> my-branch
   * refs/tags/my-tag -> my tag
   *
   * @param rawRef
   * @return git reference normalized.
   */
  public static String normalizeRef(final String rawRef) {
    if (rawRef == null || rawRef.isEmpty()) {
      return null;
    }

    String ref = rawRef;
    if (ref.startsWith("origin")) {
      ref = ref.replace("origin/", "");
    } else if (ref.startsWith("refs/heads")) {
      ref = ref.replace("refs/heads/", "");
    }

    if (ref.startsWith("refs/tags")) {
      return ref.replace("refs/tags/", "");
    } else if (ref.startsWith("tags")) {
      return ref.replace("tags/", "");
    }

    return ref;
  }

  /**
   * Removes the user info of a certain URL. E.g: https://user:password@host.com/path ->
   * https://host.com/path
   *
   * @param urlStr
   * @return url without user info.
   */
  public static String filterSensitiveInfo(final String urlStr) {
    if (urlStr == null || urlStr.isEmpty()) {
      return null;
    }

    try {
      final URI url = new URI(urlStr);
      final String userInfo = url.getRawUserInfo();
      return urlStr.replace(userInfo + "@", "");
    } catch (final URISyntaxException ex) {
      return urlStr;
    }
  }
}
