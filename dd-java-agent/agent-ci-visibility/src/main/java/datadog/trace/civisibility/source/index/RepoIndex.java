package datadog.trace.civisibility.source.index;

import datadog.trace.civisibility.ipc.Serializer;
import datadog.trace.civisibility.source.Utils;
import datadog.trace.util.ClassNameTrie;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepoIndex {

  static final RepoIndex EMPTY =
      new RepoIndex(
          ClassNameTrie.Builder.EMPTY_TRIE, Collections.emptyList(), Collections.emptyList());

  private static final Logger log = LoggerFactory.getLogger(RepoIndex.class);
  private static final int ACCESS_MODIFIERS =
      Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;

  private final LanguageDetector languageDetector;
  private final ClassNameTrie trie;
  private final List<String> sourceRoots;
  private final List<String> rootPackages;

  RepoIndex(ClassNameTrie trie, List<String> sourceRoots, List<String> rootPackages) {
    this.languageDetector = new LanguageDetector();
    this.trie = trie;
    this.sourceRoots = sourceRoots;
    this.rootPackages = rootPackages;
  }

  public List<String> getRootPackages() {
    return rootPackages;
  }

  @Nullable
  public String getSourcePath(@Nonnull Class<?> c) {
    String topLevelClassName = Utils.stripNestedClassNames(c.getName());
    Language language = languageDetector.detect(c);
    if (language == null) {
      log.debug("Could not detect language for class {}", c.getName());
      return null;
    }

    String extension = language.getExtension();
    String classNameWithExtension = topLevelClassName + extension;
    int sourceRootIdx = trie.apply(classNameWithExtension);
    if (sourceRootIdx >= 0) {
      String sourceRoot = sourceRoots.get(sourceRootIdx);
      return sourceRoot
          + File.separatorChar
          + topLevelClassName.replace('.', File.separatorChar)
          + extension;
    }

    boolean packagePrivateClass = (c.getModifiers() & ACCESS_MODIFIERS) == 0;
    if (packagePrivateClass || language != Language.JAVA) {
      return getSourcePathForPackagePrivateOrNonJavaClass(c);

    } else {
      log.debug("Could not find source root for class {}", c.getName());
      return null;
    }
  }

  @Nullable
  public String getSourcePath(String pathRelativeToSourceRoot) {
    int sourceRootIdx = trie.apply(pathRelativeToSourceRoot);
    if (sourceRootIdx >= 0) {
      return sourceRoots.get(sourceRootIdx) + File.separator + pathRelativeToSourceRoot;
    } else {
      return null;
    }
  }

  /**
   * Names of package-private classes do not have to correspond to the names of their source code
   * files. For such classes filename is extracted from SourceFile attribute that is available in
   * the compiled class.
   */
  private String getSourcePathForPackagePrivateOrNonJavaClass(Class<?> c) {
    try {
      String fileName = Utils.getFileName(c);
      Package classPackage = c.getPackage();
      String packageName = classPackage != null ? classPackage.getName() : "";
      String classNameWithExtension = packageName + File.separatorChar + fileName;

      int sourceRootIdx = trie.apply(classNameWithExtension);
      if (sourceRootIdx < 0) {
        log.debug("Could not find source root for package-private class {}", c.getName());
        return null;
      }

      String sourceRoot = sourceRoots.get(sourceRootIdx);
      return sourceRoot
          + File.separatorChar
          + packageName.replace('.', File.separatorChar)
          + File.separatorChar
          + fileName;

    } catch (IOException e) {
      log.error(
          "Error while trying to retrieve SourceFile attribute from package-private class {}",
          c.getName(),
          e);
      return null;
    }
  }

  public String getResourcePath(String relativePath) {
    int sourceRootIdx = trie.apply(relativePath);
    if (sourceRootIdx < 0) {
      log.debug("Could not find source root for resource {}", relativePath);
      return null;
    }

    String sourceRoot = sourceRoots.get(sourceRootIdx);
    return sourceRoot + File.separator + relativePath;
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
    s.write(sourceRoots);
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

    List<String> sourceRoots = Serializer.readStringList(buffer);
    List<String> rootPackages = Serializer.readStringList(buffer);
    return new RepoIndex(trie, sourceRoots, rootPackages);
  }
}
