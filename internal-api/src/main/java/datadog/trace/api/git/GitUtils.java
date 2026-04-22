package datadog.trace.api.git;

import static datadog.trace.api.git.RawParseUtils.decode;
import static datadog.trace.api.git.RawParseUtils.nextLF;

import datadog.trace.util.Strings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitUtils {

  private static final Pattern ORIGIN_PATTERN = Pattern.compile("origin/", Pattern.LITERAL);
  private static final Pattern REFS_HEADS_PATTERN = Pattern.compile("refs/heads/", Pattern.LITERAL);
  private static final Pattern REFS_TAGS_PATTERN = Pattern.compile("refs/tags/", Pattern.LITERAL);
  private static final Pattern TAGS_PATTERN = Pattern.compile("tags/", Pattern.LITERAL);
  // Based on https://git-scm.com/docs/git-check-ref-format
  private static final Pattern INVALID_REF_PATTERN =
      Pattern.compile(
          "^/" // starts with /
              + "|//" // contains //
              + "|\\.\\." // contains ..
              + "|@\\{" // contains @{
              + "|\\.$" // ends with a dot
              + "|\\.lock(/|$)" // ends with .lock in any path component
              + "|(?:^|/)\\." // any component starts with a dot
              + "|\\s" // contains space
              + "|[~^:?*\\[\\\\]" // contains ~ ^ : ? * [ \
              + "|^@$" // is only @
              + "|/$" // ends with slash
          );
  private static final Pattern PATH_PATTERN = Pattern.compile("^[a-zA-Z0-9_./-]+$");
  private static final Pattern SHELL_METACHAR_PATTERN = Pattern.compile(".*[`$&|;<>\n\r#].*");

  private static final int SHORT_SHA_LENGTH = 7;
  private static final int FULL_SHA_LENGTH = 40;

  private static final Logger log = LoggerFactory.getLogger(GitUtils.class);

  /**
   * Normalizes Git tag references:
   *
   * <ul>
   *   <li>refs/tags/my-tag -> my tag
   * </ul>
   *
   * @param rawTagRef
   * @return git reference normalized.
   */
  public static String normalizeTag(final String rawTagRef) {
    return normalizeRef(rawTagRef);
  }

  /**
   * Normalizes Git branch references:
   *
   * <ul>
   *   <li>origin/my-branch -> my-branch
   *   <li>refs/heads/my-branch -> my-branch
   * </ul>
   *
   * <p>If the reference starts with "tags/" or contains "/tags/",<code>null</code> is returned
   *
   * @param rawBranchRef
   * @return git reference normalized.
   */
  public static String normalizeBranch(final String rawBranchRef) {
    if (isTagReference(rawBranchRef)) {
      return null;
    } else {
      return normalizeRef(rawBranchRef);
    }
  }

  public static boolean isTagReference(final String ref) {
    return ref != null && (ref.startsWith("tags/") || ref.contains("/tags/"));
  }

  private static String normalizeRef(final String rawRef) {
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

  private static final Pattern SSH_PROTOCOL_AND_USER_INFO = Pattern.compile("^(ssh://).*?@");

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
      if (urlStr.startsWith("http")) {
        final URI url = new URI(urlStr);
        final String userInfo = url.getRawUserInfo();
        return Pattern.compile(userInfo + "@", Pattern.LITERAL).matcher(urlStr).replaceAll("");
      } else {
        return SSH_PROTOCOL_AND_USER_INFO.matcher(urlStr).replaceAll("$1");
      }
    } catch (final Exception ex) {
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

  /**
   * Decompress the byte array passed as argument using Java Inflater. The git objects are stored
   * using ZLib compression.
   *
   * <p>If the decompression process requires a preset dictionary or the input is not enough, we
   * return null.
   *
   * @param bytes compress data
   * @return decompressed data or null
   * @throws DataFormatException
   */
  public static byte[] inflate(final byte[] bytes) throws DataFormatException {
    try (final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

      // Git objects are compressed with ZLib.
      // We need to decompress it using Inflater.
      final Inflater ifr = new Inflater();
      try {
        ifr.setInput(bytes);
        final byte[] tmp = new byte[4 * 1024];
        while (!ifr.finished()) {
          final int size = ifr.inflate(tmp);
          if (size != 0) {
            baos.write(tmp, 0, size);
          } else {
            // Inflater can return !finished but 0 bytes inflated.
            if (ifr.needsDictionary()) {
              logErrorInflating(
                  "The data was compressed using a preset dictionary. We cannot decompress it.");
              return null;
            } else if (ifr.needsInput()) {
              logErrorInflating("The provided data is not enough. It might be corrupted");
              return null;
            } else {
              // At this point, neither dictionary nor input is needed.
              // We break the loop and we will use the decompressed data that we already have.
              break;
            }
          }
        }

        return baos.toByteArray();
      } finally {
        ifr.end();
      }
    } catch (final IOException e) {
      return null;
    }
  }

  private static void logErrorInflating(final String reason) {
    log.warn("Could not decompressed git object: Reason {}", reason);
  }

  /**
   * Checks if the provided string is a valid commit SHA:
   *
   * <ul>
   *   <li>length >= 7
   *   <li>every character is a hexadecimal digit
   * </ul>
   */
  public static boolean isValidCommitSha(final String commitSha) {
    return isValidCommitSha(commitSha, SHORT_SHA_LENGTH);
  }

  /**
   * Checks if the provided string is a valid commit SHA of full length:
   *
   * <ul>
   *   <li>length >= 40
   *   <li>every character is a hexadecimal digit
   * </ul>
   */
  public static boolean isValidCommitShaFull(final String commitSha) {
    return isValidCommitSha(commitSha, FULL_SHA_LENGTH);
  }

  private static boolean isValidCommitSha(final String commitSha, final int minLength) {
    if (commitSha == null || commitSha.length() < minLength) {
      return false;
    }
    for (char c : commitSha.toCharArray()) {
      if (!isHex(c)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  /** Checks if a string that will be used as a Git command argument is valid and safe */
  static boolean isValidArg(@Nullable String arg) {
    if (Strings.isBlank(arg)) {
      return false;
    }
    return !SHELL_METACHAR_PATTERN.matcher(arg).find();
  }

  /** Checks if the provided string is a valid Git reference (branch, tag, etc.) */
  public static boolean isValidRef(@Nullable String ref) {
    if (Strings.isBlank(ref)) {
      return false;
    }

    for (char c : ref.toCharArray()) {
      if (c < 32 || c == 127) return false;
    }

    if (INVALID_REF_PATTERN.matcher(ref).find()) {
      return false;
    }
    return isValidArg(ref);
  }

  /** Checks if the provided string is a valid system path for Git operations */
  public static boolean isValidPath(@Nonnull String path) {
    if (!PATH_PATTERN.matcher(path).matches()) {
      return false;
    }
    // Reject path traversal sequences
    return !path.contains("..");
  }

  /** Checks if the provided string is neither a valid commit SHA nor a valid Git reference */
  public static boolean isNotValidCommit(@Nullable String commit) {
    return !isValidCommitSha(commit) && !isValidRef(commit);
  }
}
