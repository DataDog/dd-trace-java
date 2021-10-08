package datadog.trace.bootstrap.instrumentation.ci.git;

import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.decode;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.nextLF;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
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

  /**
   * Splits the git author in the form `John Doe <john.doe@email.com>` in a PersonInfo with name:
   * John Doe and email: john.doe@email.com
   *
   * @param rawAuthor
   * @return PersonInfo
   */
  public static PersonInfo splitAuthorAndEmail(String rawAuthor) {
    if (rawAuthor == null || rawAuthor.isEmpty()) {
      return PersonInfo.NOOP;
    }

    final byte[] raw = rawAuthor.getBytes(StandardCharsets.UTF_8);
    final int nameB = 0;

    // First, we find the index where the email starts and ends:
    final int emailB = nextLF(raw, nameB, '<');
    final int emailE = nextLF(raw, emailB, '>');
    if (emailB >= raw.length
        || raw[emailB] == '\n'
        || (emailE >= raw.length - 1 && raw[emailE - 1] != '>')) {
      return PersonInfo.NOOP;
    }

    // We need to find which is the index where the name ends,
    // using the relative position where the email starts.
    final int nameEnd = emailB - 2 >= nameB && raw[emailB - 2] == ' ' ? emailB - 2 : emailB - 1;

    // Once we have the indexes where the name starts and ends
    // we can extract the name.
    final String name = decode(raw, nameB, nameEnd);

    // Same approach to extract the email, using the indexes
    // where the email starts and ends.
    final String email = decode(raw, emailB, emailE - 1);

    return new PersonInfo(name, email);
  }
}
