package datadog.trace.bootstrap.instrumentation.ci.git;

import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.author;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.commitMessage;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.committer;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.decode;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.findByte;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.lastIndexOfTrim;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.nextLF;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.parseLongBase10;
import static datadog.trace.bootstrap.instrumentation.ci.git.RawParseUtils.parseTimeZoneOffset;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Extracts git information from the local filesystem. Typically, we will use this extractor using
 * the local fs .git folder path as starting point.
 */
public class LocalFSGitInfoExtractor implements GitInfoExtractor {

  private static final int TYPE_INDEX = 0;
  private static final int SIZE_INDEX = 1;

  private static final int SHA_INDEX = 1;

  /**
   * Extracts all git information available from the HEAD
   *
   * @param gitFolder the git folder path as String.
   * @return {@code GitInfo} object with all git available info from the HEAD object.
   */
  @Override
  public GitInfo headCommit(final String gitFolder) {
    try {
      final Path gitFolderPath = Paths.get(gitFolder);
      final String repositoryURL = extractRepositoryURL(gitFolderPath);
      final String head = readFile(gitFolderPath.resolve("HEAD"));
      final String ref = extractRef(head);
      final String branch = extractBranch(ref);
      final String tag = extractTag(ref);
      final String sha = extractSha(gitFolderPath, head);
      final CommitInfo commitInfo = findCommit(gitFolder, sha);

      return GitInfo.builder()
          .repositoryURL(repositoryURL)
          .branch(branch)
          .tag(tag)
          .commit(commitInfo)
          .build();
    } catch (final Exception e) {
      return GitInfo.NOOP;
    }
  }

  private String extractRepositoryURL(final Path gitFolderPath) {
    final File configFile = gitFolderPath.resolve("config").toFile();
    if (!configFile.exists()) {
      return null;
    }

    final GitConfig gitConfig = new GitConfig(configFile.getAbsolutePath());
    return GitUtils.filterSensitiveInfo(gitConfig.getString("remote \"origin\"", "url"));
  }

  private String extractTag(final String ref) {
    if (ref != null && ref.contains("refs/tags")) {
      return GitUtils.normalizeRef(ref);
    }
    return null;
  }

  private String extractBranch(final String ref) {
    if (ref != null && (ref.contains("origin") || ref.contains("refs/heads"))) {
      return GitUtils.normalizeRef(ref);
    }
    return null;
  }

  private String extractRef(final String head) {
    if (head == null || head.isEmpty() || !head.contains("ref:")) {
      return null;
    }

    // The HEAD file contains a reference: e.g: ref: /refs/head/master
    return head.substring(5); // Remove the ref: prefix
  }

  private CommitInfo findCommit(final String gitFolder, final String sha)
      throws IOException, DataFormatException {
    if (sha == null || sha.isEmpty()) {
      return CommitInfo.NOOP;
    }

    // We access to the Git object represented by the commit sha.
    // In Git, the 2 first characters of the sha corresponds with the folder. The rest of the sha,
    // corresponds with the file name.
    // Commit: 44c242675ddf69b7b1f440b4a5d8d24e908d8bef -> Object:
    // .git/objects/44/c242675ddf69b7b1f440b4a5d8d24e908d8bef
    final String folder = sha.substring(0, 2);
    final String filename = sha.substring(2);
    final File gitObjectFile = Paths.get(gitFolder, "objects", folder, filename).toFile();
    if (!gitObjectFile.exists()) {
      return CommitInfo.NOOP;
    }

    final byte[] deflatedBytes = Files.readAllBytes(gitObjectFile.toPath());
    // Decompress (inflate) the Git object.
    final GitObject gitObject = inflateGitObject(deflatedBytes);
    return parseCommit(gitFolder, sha, gitObject);
  }

  private CommitInfo parseCommit(
      final String gitFolder, final String sha, final GitObject gitObject)
      throws IOException, DataFormatException {
    if ("tag".equalsIgnoreCase(gitObject.getType())) {
      // If the Git object is a tag, we need to read which sha is being referenced within the tag
      // object content.

      // The referenced object is in the first bytes until the first LF ("object $sha1\ntype ")
      final int lf = nextLF(gitObject.getContent(), 0);
      if (lf == -1) {
        return CommitInfo.NOOP;
      }

      // We get the reference in the object getting the bytes until the \n character:
      final String objectSha = new String(Arrays.copyOfRange(gitObject.getContent(), 0, lf - 1));

      // Here, objectSha = "object $sha1". E.g: "object 44c242675ddf69b7b1f440b4a5d8d24e908d8bef"
      // Split by " " and get the sha in the second position of the array
      final String[] objectShaChunks = objectSha.split(" ");
      if (objectShaChunks.length < 2) {
        return CommitInfo.NOOP;
      }

      final String innerSha = objectShaChunks[SHA_INDEX];
      return findCommit(gitFolder, innerSha);

    } else if (!"commit".equalsIgnoreCase(gitObject.getType())) {
      return CommitInfo.NOOP;
    }

    final byte[] content = gitObject.getContent();
    final PersonInfo author = getAuthor(content);
    final PersonInfo committer = getCommitter(content);
    final String fullMessage = getFullMessage(content);

    return CommitInfo.builder()
        .sha(sha)
        .author(author)
        .committer(committer)
        .fullMessage(fullMessage)
        .build();
  }

  private GitObject inflateGitObject(final byte[] bytes) throws DataFormatException {
    try (final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

      // Git objects are compressed with ZLib.
      // We need to decompress it using Inflater.
      final Inflater ifr = new Inflater();
      ifr.setInput(bytes);

      final byte[] tmp = new byte[4 * 1024];
      while (!ifr.finished()) {
        final int size = ifr.inflate(tmp);
        baos.write(tmp, 0, size);
      }

      final byte[] decompressed = baos.toByteArray();

      // The ((byte) 0) separates the metadata and the content
      // in the decompressed git object.
      final int separatorIndex = findByte(decompressed, (byte) 0);
      if (separatorIndex == -1) {
        // We cannot find the separator.
        return GitObject.NOOP;
      }

      // Getting the metadata from 0 to separator index
      final byte[] metadataBytes = Arrays.copyOfRange(decompressed, 0, separatorIndex);

      // The metadata has the type and the size of the git object separated by the space character
      // ((byte)32)
      // metadata[0] contains the type (e.g. commit)
      // metadata[1] contains the size (e.g. 261)
      final String[] metadata = new String(metadataBytes).split(" ");
      if (metadata.length != 2) {
        // Unexpected metadata format.
        return GitObject.NOOP;
      }

      // Getting the content from separator index to the end of decompressed byte array.
      final byte[] content =
          Arrays.copyOfRange(decompressed, separatorIndex + 1, decompressed.length);

      return GitObject.builder()
          .type(metadata[TYPE_INDEX])
          .size(Integer.parseInt(metadata[SIZE_INDEX]))
          .content(content)
          .build();
    } catch (final IOException e) {
      return GitObject.NOOP;
    }
  }

  private String extractSha(final Path gitFolder, final String head) throws IOException {
    // HEAD can contain a reference (e.g.: refs/heads/master) or
    // a SHA (e.g.: 6ba9a670e26a69ae26bafd2409ae200d152afa76)

    if (head.contains("ref:")) {
      final String refStr = extractRef(head);
      if (refStr == null) {
        return null;
      }

      // If the HEAD contains a reference, we need to access to
      // the content of that reference which will contain the SHA.
      final File ref = gitFolder.resolve(refStr).toFile();
      if (!ref.exists()) {
        return null;
      }
      return readFile(ref.toPath());
    }

    return head;
  }

  protected PersonInfo getAuthor(final byte[] buffer) {
    // Locate the index where the author name begins.
    final int authorNameBeginning = author(buffer, 0);
    if (authorNameBeginning < 0) {
      return PersonInfo.NOOP;
    }

    // Starting from the author name beginning index,
    // we parse the "person" info of the author.
    return parsePersonInfo(buffer, authorNameBeginning);
  }

  protected PersonInfo getCommitter(final byte[] buffer) {
    // Locate the index where the committer name begins.
    final int nameB = committer(buffer, 0);
    if (nameB < 0) {
      return PersonInfo.NOOP;
    }

    // Starting from the committer name beginning index,
    // we parse the "person" info of the author.
    return parsePersonInfo(buffer, nameB);
  }

  protected String getFullMessage(final byte[] buffer) {
    // Locate the index where the commit message begins.
    final int msgB = commitMessage(buffer, 0);
    if (msgB < 0) {
      return null;
    }

    // Starting from the commit message beginning index,
    // we parse the "person" info of the author.
    return decode(buffer, msgB, buffer.length);
  }

  protected PersonInfo parsePersonInfo(final byte[] raw, final int nameB) {
    // Typically, the line which contains
    // the person information looks like:
    // author John Doe <john@doe.com> 1613137668 +0100
    // committer Jane Doe <jane@doe.com> 1613137724 +0100

    // First, we find the index where the email starts and ends:
    final int emailB = nextLF(raw, nameB, '<');
    final int emailE = nextLF(raw, emailB, '>');
    if (emailB >= raw.length
        || raw[emailB] == '\n'
        || (emailE >= raw.length - 1 && raw[emailE - 1] != '>')) {
      return null;
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

    // Start searching from end of line, as after first name-email pair,
    // another name-email pair may occur. We will ignore all kinds of
    // "junk" following the first email.
    //
    // We've to use (emailE - 1) for the case that raw[email] is LF,
    // otherwise we would run too far. "-2" is necessary to position
    // before the LF in case of LF termination resp. the penultimate
    // character if there is no trailing LF.
    final int tzBegin = lastIndexOfTrim(raw, ' ', nextLF(raw, emailE - 1) - 2) + 1;
    if (tzBegin <= emailE) // No time/zone, still valid
    {
      return PersonInfo.builder().name(name).email(email).when(0).tzOffset(0).build();
    }

    final int whenBegin = Math.max(emailE, lastIndexOfTrim(raw, ' ', tzBegin - 1) + 1);
    if (whenBegin >= tzBegin - 1) // No time/zone, still valid
    {
      return PersonInfo.builder().name(name).email(email).when(0).tzOffset(0).build();
    }

    final long when = parseLongBase10(raw, whenBegin);
    final int tz = parseTimeZoneOffset(raw, tzBegin);
    return PersonInfo.builder().name(name).email(email).when(when * 1000L).tzOffset(tz).build();
  }

  private String readFile(final Path filepath) throws IOException {
    if (filepath == null || !filepath.toFile().exists()) {
      return null;
    }

    final String content = new String(Files.readAllBytes(filepath));
    if (content.endsWith("\n")) {
      return content.substring(0, content.length() - 1);
    }

    return content;
  }
}
