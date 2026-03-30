package datadog.trace.civisibility.source.index;

import datadog.instrument.utils.ClassNameTrie;
import datadog.trace.api.Config;
import datadog.trace.api.civisibility.domain.Language;
import datadog.trace.civisibility.ipc.serialization.Serializer;
import datadog.trace.civisibility.source.Utils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepoIndex {

  static final RepoIndex EMPTY =
      new RepoIndex(
          ClassNameTrie.Builder.EMPTY_TRIE,
          Collections.emptyMap(),
          Collections.emptyList(),
          Collections.emptyList());

  private static final Logger log = LoggerFactory.getLogger(RepoIndex.class);

  private final ClassNameTrie trie;
  private final Map<String, List<String>> duplicateTrieKeys;
  private final List<SourceRoot> sourceRoots;
  private final List<String> rootPackages;

  RepoIndex(
      ClassNameTrie trie,
      Map<String, List<String>> duplicateTrieKeys,
      List<SourceRoot> sourceRoots,
      List<String> rootPackages) {
    this.trie = trie;
    this.duplicateTrieKeys = duplicateTrieKeys;
    this.sourceRoots = sourceRoots;
    this.rootPackages = rootPackages;
  }

  public List<String> getRootPackages() {
    return rootPackages;
  }

  public Collection<String> getSourcePaths(@Nonnull Class<?> c) {
    String topLevelClassName = Utils.stripNestedClassNames(c.getName());
    Collection<String> sourcePaths = doGetAllSourcePaths(topLevelClassName);
    return !sourcePaths.isEmpty() ? sourcePaths : getFallbackSourcePaths(c);
  }

  /**
   * Used as a fallback for non-Java classes or Java classes that are not public: in this case class
   * name does not necessarily correspond to the source file name, so source file name needs to be
   * retrieved from the bytecode.
   */
  private Collection<String> getFallbackSourcePaths(@Nonnull Class<?> c) {
    try {
      String fileName = Utils.getFileName(c);
      if (fileName == null) {
        log.debug("Could not retrieve file name for class {}", c.getName());
        return Collections.emptyList();
      }

      String fileNameWithoutExtension = Utils.stripExtension(fileName);
      Package classPackage = c.getPackage();
      String packageName = classPackage != null ? classPackage.getName() : "";
      String key = packageName + '.' + fileNameWithoutExtension;
      return doGetAllSourcePaths(key);

    } catch (IOException e) {
      log.error("Error while trying to retrieve file name for class {}", c.getName(), e);
      return Collections.emptyList();
    }
  }

  public Collection<String> getSourcePaths(@Nullable String pathRelativeToSourceRoot) {
    if (pathRelativeToSourceRoot == null) {
      return Collections.emptyList();
    }
    String key = Utils.toTrieKey(pathRelativeToSourceRoot);
    return doGetAllSourcePaths(key);
  }

  private Collection<String> doGetAllSourcePaths(String key) {
    if (Config.get().isCiVisibilityRepoIndexDuplicateKeyCheckEnabled()
        && !duplicateTrieKeys.isEmpty()
        && duplicateTrieKeys.containsKey(key)) {
      List<String> paths = duplicateTrieKeys.get(key);
      log.debug(
          "Duplicate trie key {} resolved to {} candidate paths: {}", key, paths.size(), paths);
      return paths;
    }

    int sourceRootIdx = trie.apply(key);
    if (sourceRootIdx < 0) {
      log.debug("Could not find source root for {}", key);
      return Collections.emptyList();
    }
    SourceRoot sourceRoot = sourceRoots.get(sourceRootIdx);
    return Collections.singletonList(sourceRoot.resolveSourcePath(key));
  }

  public ByteBuffer serialize() {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream)) {
      ClassNameTrie.Builder builder = new ClassNameTrie.Builder(trie);
      builder.writeTo(dataOutputStream);
    } catch (IOException e) {
      // should not happen ever, since we're writing to byte array stream
      throw new RuntimeException("Could not serialize classname trie", e);
    }
    byte[] serializedTrie = byteArrayOutputStream.toByteArray();

    Serializer s = new Serializer();
    s.write(serializedTrie);
    s.write(duplicateTrieKeys, Serializer::write, (ser, paths) -> ser.write(paths));
    s.write(sourceRoots, SourceRoot::serialize);
    s.write(rootPackages);
    return s.flush();
  }

  public static RepoIndex deserialize(ByteBuffer buffer) {
    ClassNameTrie trie;

    byte[] trieBytes = Serializer.readByteArray(buffer);
    if (trieBytes == null) {
      trie = null;

    } else {
      ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(trieBytes);
      try (DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream)) {
        trie = ClassNameTrie.readFrom(dataInputStream);
      } catch (IOException e) {
        // should not happen ever, since we're writing to byte array stream
        throw new RuntimeException("Could not deserialize classname trie", e);
      }
    }

    Map<String, List<String>> duplicateTrieKeys =
        Serializer.readMap(buffer, Serializer::readString, Serializer::readStringList);
    List<SourceRoot> sourceRoots = Serializer.readList(buffer, SourceRoot::deserialize);
    List<String> rootPackages = Serializer.readStringList(buffer);
    return new RepoIndex(trie, duplicateTrieKeys, sourceRoots, rootPackages);
  }

  static final class SourceRoot {
    /** Path relative to repository root. */
    final String relativePath;

    final Language language;

    SourceRoot(String relativePath, Language language) {
      this.relativePath = relativePath;
      this.language = language;
    }

    /** Resolves a trie key (dot-separated) to a full source path relative to the source root. */
    String resolveSourcePath(String trieKey) {
      return relativePath
          + File.separatorChar
          + trieKey.replace('.', File.separatorChar)
          + language.getExtension();
    }

    static void serialize(Serializer s, SourceRoot sourceRoot) {
      s.write(sourceRoot.relativePath);
      s.write(sourceRoot.language.ordinal());
    }

    static SourceRoot deserialize(ByteBuffer buffer) {
      String relativePath = Serializer.readString(buffer);
      Language language = Language.getByOrdinal(Serializer.readInt(buffer));
      return new SourceRoot(relativePath, language);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SourceRoot that = (SourceRoot) o;
      return Objects.equals(relativePath, that.relativePath) && language == that.language;
    }

    @Override
    public int hashCode() {
      return Objects.hash(relativePath, language);
    }
  }
}
