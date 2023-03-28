package datadog.trace.civisibility.git;

import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitUtils;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.api.git.RawParseUtils;
import datadog.trace.civisibility.git.pack.GitPackObject;
import datadog.trace.civisibility.git.pack.GitPackUtils;
import datadog.trace.civisibility.git.pack.V2PackGitInfoExtractor;
import datadog.trace.civisibility.git.pack.VersionedPackGitInfoExtractor;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;

/**
 * Extracts git information from the local filesystem. Typically, we will use this extractor using
 * the local fs .git folder path as starting point.
 *
 * <p>Some methods were adapted from
 * https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/revwalk/RevCommit.java
 * https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/util/RawParseUtils.java
 */
public class LocalFSGitInfoExtractor implements GitInfoExtractor {

  private static final int SHA_INDEX = 1;

  private static final VersionedPackGitInfoExtractor V2_PACK_GIT_INFO_EXTRACTOR =
      new V2PackGitInfoExtractor();

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
      final String head = readFile(gitFolderPath.resolve("HEAD"));
      final String ref = extractRef(head);
      final String branch = extractBranch(ref);
      final String tag = extractTag(ref);
      final String sha = extractSha(gitFolderPath, head);
      final String repositoryURL = extractRepositoryURL(gitFolderPath, branch);
      final CommitInfo commitInfo = findCommit(gitFolder, sha);

      return new GitInfo(repositoryURL, branch, tag, commitInfo);
    } catch (final Exception e) {
      return GitInfo.NOOP;
    }
  }

  private String extractRepositoryURL(final Path gitFolderPath, final String branch) {
    final File configFile = gitFolderPath.resolve("config").toFile();
    if (!configFile.exists()) {
      return null;
    }

    final GitConfig gitConfig = new GitConfig(configFile.getAbsolutePath());
    final String branchRemote = gitConfig.getString("branch \"" + branch + "\"", "remote");
    String remoteUrl = gitConfig.getString("remote \"" + branchRemote + "\"", "url");
    if (remoteUrl == null) {
      remoteUrl = gitConfig.getString("remote \"origin\"", "url");
    }
    return GitUtils.filterSensitiveInfo(remoteUrl);
  }

  private String extractTag(final String ref) {
    if (ref != null && ref.contains("refs/tags")) {
      return GitUtils.normalizeTag(ref);
    }
    return null;
  }

  private String extractBranch(final String ref) {
    if (ref != null && (ref.contains("origin") || ref.contains("refs/heads"))) {
      return GitUtils.normalizeBranch(ref);
    }
    return null;
  }

  private String extractRef(final String head) {
    if (head == null || !head.contains("ref:")) {
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

    final GitObject gitObject;
    if (gitObjectFile.exists()) {
      final byte[] deflatedBytes = Files.readAllBytes(gitObjectFile.toPath());
      gitObject = buildGitObject(deflatedBytes);
    } else {
      final GitPackObject gitPackObject = readPackObject(gitFolder, sha);
      gitObject = buildGitObject(gitPackObject);
    }

    if (gitObject == null) {
      return CommitInfo.NOOP;
    }

    return parseCommit(gitFolder, sha, gitObject);
  }

  private GitPackObject readPackObject(final String gitFolder, final String sha)
      throws IOException {
    final File packFolder = Paths.get(gitFolder, "objects", "pack").toFile();
    final File[] idxFiles =
        packFolder.listFiles(
            new FilenameFilter() {
              @Override
              public boolean accept(final File dir, final String name) {
                return name.endsWith(".idx");
              }
            });

    if (idxFiles == null) {
      return null;
    }

    short packVersion = 0;
    for (final File idxFile : idxFiles) {
      // We assume that if an IDX file has a certain version, the rest of IDXs file will be based on
      // the same version.
      if (packVersion == 0) {
        packVersion = GitPackUtils.extractGitPackVersion(idxFile);
      }

      final VersionedPackGitInfoExtractor gitPackExtractor = lookupExtractor(packVersion);
      if (gitPackExtractor == null) {
        break;
      }

      final GitPackObject packObj =
          gitPackExtractor.extract(idxFile, GitPackUtils.getPackFile(idxFile), sha);
      if (packObj.raisedError()) {
        // If an error is raised, we don't want to continue checking the next packfiles.
        return null;
      }

      if (packObj.getShaIndex() == GitPackObject.NOT_FOUND_SHA_INDEX) {
        // If the commit sha has not been found in this idx file, continue with the next one.
        continue;
      }

      return packObj;
    }
    return null;
  }

  private static final Pattern SPACE_PATTERN = Pattern.compile(" ");

  private CommitInfo parseCommit(
      final String gitFolder, final String sha, final GitObject gitObject)
      throws IOException, DataFormatException {
    if (gitObject.getType() == GitObject.TAG_TYPE) {
      // If the Git object is a tag, we need to read which sha is being referenced within the tag
      // object content.

      // The referenced object is in the first bytes until the first LF ("object $sha1\ntype ")
      final int lf = RawParseUtils.nextLF(gitObject.getContent(), 0);
      if (lf == -1) {
        return CommitInfo.NOOP;
      }

      // We get the reference in the object getting the bytes until the \n character:
      final String objectSha = new String(Arrays.copyOfRange(gitObject.getContent(), 0, lf - 1));

      // Here, objectSha = "object $sha1". E.g: "object 44c242675ddf69b7b1f440b4a5d8d24e908d8bef"
      // Split by " " and get the sha in the second position of the array
      final String[] objectShaChunks = SPACE_PATTERN.split(objectSha);
      if (objectShaChunks.length < 2) {
        return CommitInfo.NOOP;
      }

      final String innerSha = objectShaChunks[SHA_INDEX];
      return findCommit(gitFolder, innerSha);

    } else if (gitObject.getType() != GitObject.COMMIT_TYPE) {
      return CommitInfo.NOOP;
    }

    final byte[] content = gitObject.getContent();
    final PersonInfo author = getAuthor(content);
    final PersonInfo committer = getCommitter(content);
    final String fullMessage = getFullMessage(content);

    return new CommitInfo(sha, author, committer, fullMessage);
  }

  private GitObject buildGitObject(final byte[] compressedBytes) {
    try {
      final byte[] decompressed = GitUtils.inflate(compressedBytes);
      if (decompressed == null) {
        return GitObject.NOOP;
      }

      // The ((byte) 0) separates the metadata and the content
      // in the decompressed git object.
      final int separatorIndex = RawParseUtils.findByte(decompressed, (byte) 0);
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
      final String[] metadata = SPACE_PATTERN.split(new String(metadataBytes));
      if (metadata.length != 2) {
        // Unexpected metadata format.
        return GitObject.NOOP;
      }

      // Getting the content from separator index to the end of decompressed byte array.
      final byte[] content =
          Arrays.copyOfRange(decompressed, separatorIndex + 1, decompressed.length);

      return new GitObject(
          typeToByte(metadata[VersionedPackGitInfoExtractor.TYPE_INDEX]),
          Integer.parseInt(metadata[VersionedPackGitInfoExtractor.SIZE_INDEX]),
          content);
    } catch (final DataFormatException ex) {
      return GitObject.NOOP;
    }
  }

  private GitObject buildGitObject(final GitPackObject gitPackObject) {
    if (gitPackObject == null) {
      return GitObject.NOOP;
    }

    try {
      final byte[] decompressed = GitUtils.inflate(gitPackObject.getDeflatedContent());
      if (decompressed == null) {
        return GitObject.NOOP;
      }

      return new GitObject(gitPackObject.getType(), decompressed.length, decompressed);
    } catch (final DataFormatException ex) {
      return GitObject.NOOP;
    }
  }

  private static byte typeToByte(final String type) {
    switch (type) {
      case "commit":
        return GitObject.COMMIT_TYPE;
      case "tag":
        return GitObject.TAG_TYPE;
      default:
        return GitObject.UNKNOWN_TYPE;
    }
  }

  private String extractSha(final Path gitFolder, final String head) throws IOException {
    if (head == null) {
      return null;
    }

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

  // Adapted from getAuthorIdent()
  // https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/revwalk/RevCommit.java
  protected PersonInfo getAuthor(final byte[] buffer) {
    // Locate the index where the author name begins.
    final int authorNameBeginning = RawParseUtils.author(buffer, 0);
    if (authorNameBeginning < 0) {
      return PersonInfo.NOOP;
    }

    // Starting from the author name beginning index,
    // we parse the "person" info of the author.
    return parsePersonInfo(buffer, authorNameBeginning);
  }

  // Adapted from getCommitterIdent()
  // https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/revwalk/RevCommit.java
  protected PersonInfo getCommitter(final byte[] buffer) {
    // Locate the index where the committer name begins.
    final int nameB = RawParseUtils.committer(buffer, 0);
    if (nameB < 0) {
      return PersonInfo.NOOP;
    }

    // Starting from the committer name beginning index,
    // we parse the "person" info of the author.
    return parsePersonInfo(buffer, nameB);
  }

  // Adapted from getFullMessage()
  // https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/revwalk/RevCommit.java
  protected String getFullMessage(final byte[] buffer) {
    // Locate the index where the commit message begins.
    final int msgB = RawParseUtils.commitMessage(buffer, 0);
    if (msgB < 0) {
      return null;
    }

    // Starting from the commit message beginning index,
    // we parse the "person" info of the author.
    return RawParseUtils.decode(buffer, msgB, buffer.length);
  }

  // Adapted from parsePersonIdent()
  // https://github.com/eclipse/jgit/blob/master/org.eclipse.jgit/src/org/eclipse/jgit/util/RawParseUtils.java
  protected PersonInfo parsePersonInfo(final byte[] raw, final int nameB) {
    // Typically, the line which contains
    // the person information looks like:
    // author John Doe <john@doe.com> 1613137668 +0100
    // committer Jane Doe <jane@doe.com> 1613137724 +0100

    // First, we find the index where the email starts and ends:
    final int emailB = RawParseUtils.nextLF(raw, nameB, '<');
    final int emailE = RawParseUtils.nextLF(raw, emailB, '>');
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
    final String name = RawParseUtils.decode(raw, nameB, nameEnd);

    // Same approach to extract the email, using the indexes
    // where the email starts and ends.
    final String email = RawParseUtils.decode(raw, emailB, emailE - 1);

    // Start searching from end of line, as after first name-email pair,
    // another name-email pair may occur. We will ignore all kinds of
    // "junk" following the first email.
    //
    // We've to use (emailE - 1) for the case that raw[email] is LF,
    // otherwise we would run too far. "-2" is necessary to position
    // before the LF in case of LF termination resp. the penultimate
    // character if there is no trailing LF.
    final int tzBegin =
        RawParseUtils.lastIndexOfTrim(raw, ' ', RawParseUtils.nextLF(raw, emailE - 1) - 2) + 1;
    if (tzBegin <= emailE) // No time/zone, still valid
    {
      return new PersonInfo(name, email, 0, 0);
    }

    final int whenBegin =
        Math.max(emailE, RawParseUtils.lastIndexOfTrim(raw, ' ', tzBegin - 1) + 1);
    if (whenBegin >= tzBegin - 1) // No time/zone, still valid
    {
      return new PersonInfo(name, email, 0, 0);
    }

    final long when = RawParseUtils.parseLongBase10(raw, whenBegin);
    final int tz = RawParseUtils.parseTimeZoneOffset(raw, tzBegin);
    return new PersonInfo(name, email, when * 1000L, tz);
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

  private static VersionedPackGitInfoExtractor lookupExtractor(final short packVersion) {
    if (packVersion == V2PackGitInfoExtractor.VERSION) {
      return V2_PACK_GIT_INFO_EXTRACTOR;
    } else {
      return null;
    }
  }
}
