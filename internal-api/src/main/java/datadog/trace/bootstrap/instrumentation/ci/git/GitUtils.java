package datadog.trace.bootstrap.instrumentation.ci.git;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

public class GitUtils {

  private static final Pattern ORIGIN_PATTERN = Pattern.compile("origin/", Pattern.LITERAL);
  private static final Pattern REFS_HEADS_PATTERN = Pattern.compile("refs/heads/", Pattern.LITERAL);
  private static final Pattern REFS_TAGS_PATTERN = Pattern.compile("refs/tags/", Pattern.LITERAL);
  private static final Pattern TAGS_PATTERN = Pattern.compile("tags/", Pattern.LITERAL);

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
      ref = ORIGIN_PATTERN.matcher(ref).replaceAll("");
    } else if (ref.startsWith("refs/heads")) {
      ref = REFS_HEADS_PATTERN.matcher(ref).replaceAll("");
    }

    if (ref.startsWith("refs/tags")) {
      return REFS_TAGS_PATTERN.matcher(ref).replaceAll("");
    } else if (ref.startsWith("tags")) {
      return TAGS_PATTERN.matcher(ref).replaceAll("");
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
      return Pattern.compile(userInfo + "@", Pattern.LITERAL).matcher(urlStr).replaceAll("");
    } catch (final URISyntaxException ex) {
      return urlStr;
    }
  }
}
