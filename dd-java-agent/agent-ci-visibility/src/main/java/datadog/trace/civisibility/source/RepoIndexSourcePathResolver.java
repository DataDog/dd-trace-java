package datadog.trace.civisibility.source;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.source.SourcePathResolver;
import datadog.trace.util.ClassNameTrie;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RepoIndexSourcePathResolver implements SourcePathResolver {

  private static final Logger log = LoggerFactory.getLogger(RepoIndexSourcePathResolver.class);

  private static final int ACCESS_MODIFIERS =
      Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE;

  private final String repoRoot;
  private final SourceRootResolver sourceRootResolver;
  private final FileSystem fileSystem;

  private final Object indexInitializationLock = new Object();
  private volatile RepoIndex index;

  public RepoIndexSourcePathResolver(String repoRoot) {
    this(repoRoot, new SourceRootResolverImpl(), FileSystems.getDefault());
  }

  RepoIndexSourcePathResolver(
      String repoRoot, SourceRootResolver sourceRootResolver, FileSystem fileSystem) {
    this.repoRoot = repoRoot;
    this.sourceRootResolver = sourceRootResolver;
    this.fileSystem = fileSystem;
  }

  @Nullable
  @Override
  public String getSourcePath(@Nonnull Class<?> c) {
    if (Config.get().isCiVisibilitySourceDataRootCheckEnabled() && !isLocatedInsideRepository(c)
        || implementsContextAccessor(c)) {
      return null; // fast exit to avoid expensive index building
    }

    if (index == null) {
      synchronized (indexInitializationLock) {
        if (index == null) {
          buildIndex();
        }
      }
    }

    String topLevelClassName = stripNestedClassNames(c.getName());
    SourceType sourceType = detectSourceType(c);
    String extension = sourceType.getExtension();
    String classNameWithExtension = topLevelClassName + extension;
    int sourceRootIdx = index.trie.apply(classNameWithExtension);
    if (sourceRootIdx >= 0) {
      String sourceRoot = index.sourceRoots.get(sourceRootIdx);
      return sourceRoot
          + File.separatorChar
          + topLevelClassName.replace('.', File.separatorChar)
          + extension;
    }

    boolean packagePrivateClass = (c.getModifiers() & ACCESS_MODIFIERS) == 0;
    if (packagePrivateClass || sourceType != SourceType.JAVA) {
      return getSourcePathForPackagePrivateOrNonJavaClass(c);

    } else {
      log.debug("Could not find source root for class {}", c.getName());
      return null;
    }
  }

  private boolean isLocatedInsideRepository(Class<?> c) {
    ProtectionDomain protectionDomain = c.getProtectionDomain();
    if (protectionDomain == null) {
      return false; // no source location data
    }
    CodeSource codeSource = protectionDomain.getCodeSource();
    if (codeSource == null) {
      return false; // no source location data
    }
    URL location = codeSource.getLocation();
    if (location == null) {
      return false; // no source location data
    }
    String file = location.getFile();
    if (file == null) {
      return false; // no source location data
    }
    return file.startsWith(repoRoot);
  }

  private static boolean implementsContextAccessor(Class<?> c) {
    for (Class<?> intf : c.getInterfaces()) {
      if ("datadog.trace.bootstrap.FieldBackedContextAccessor".equals(intf.getName())) {
        // dynamically generated accessor class for bytecode-injected field
        return true;
      }
    }
    return false;
  }

  private SourceType detectSourceType(Class<?> c) {
    Class<?>[] interfaces = c.getInterfaces();
    for (Class<?> anInterface : interfaces) {
      String interfaceName = anInterface.getName();
      if ("groovy.lang.GroovyObject".equals(interfaceName)) {
        return SourceType.GROOVY;
      }
    }

    Annotation[] annotations = c.getAnnotations();
    for (Annotation annotation : annotations) {
      Class<? extends Annotation> annotationType = annotation.annotationType();
      if ("kotlin.Metadata".equals(annotationType.getName())) {
        return SourceType.KOTLIN;
      }
    }

    // assuming Java
    return SourceType.JAVA;
  }

  private String stripNestedClassNames(String className) {
    int innerClassNameIdx = className.indexOf('$');
    if (innerClassNameIdx >= 0) {
      return className.substring(0, innerClassNameIdx);
    } else {
      return className;
    }
  }

  /**
   * Names of package-private classes do not have to correspond to the names of their source code
   * files. For such classes filename is extracted from SourceFile attribute that is available in
   * the compiled class.
   */
  private String getSourcePathForPackagePrivateOrNonJavaClass(Class<?> c) {
    try {
      SourceFileAttributeVisitor sourceFileAttributeVisitor = new SourceFileAttributeVisitor();

      try (InputStream classStream = Utils.getClassStream(c)) {
        ClassReader classReader = new ClassReader(classStream);
        classReader.accept(
            sourceFileAttributeVisitor, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
      }

      String source = sourceFileAttributeVisitor.getSource();
      String packageName = c.getPackage().getName();
      String classNameWithExtension = packageName + File.separatorChar + source;

      int sourceRootIdx = index.trie.apply(classNameWithExtension);
      if (sourceRootIdx < 0) {
        log.debug("Could not find source root for package-private class {}", c.getName());
        return null;
      }

      String sourceRoot = index.sourceRoots.get(sourceRootIdx);
      return sourceRoot
          + File.separatorChar
          + packageName.replace('.', File.separatorChar)
          + File.separatorChar
          + source;

    } catch (IOException e) {
      log.error(
          "Error while trying to retrieve SourceFile attribute from package-private class {}",
          c.getName(),
          e);
      return null;
    }
  }

  private void buildIndex() {
    log.warn(
        "Building index of source files in {}. "
            + "This operation can be slow, "
            + "please consider using Datadog Java compiler plugin to avoid indexing",
        repoRoot);

    Path repoRootPath = fileSystem.getPath(repoRoot);
    RepoIndexingFileVisitor repoIndexingFileVisitor =
        new RepoIndexingFileVisitor(sourceRootResolver, repoRootPath);

    long startTime = System.currentTimeMillis();
    try {
      Files.walkFileTree(
          repoRootPath,
          EnumSet.of(FileVisitOption.FOLLOW_LINKS),
          Integer.MAX_VALUE,
          repoIndexingFileVisitor);
    } catch (Exception e) {
      log.error("Failed to build index repo of {}", repoRoot, e);
    }

    index = repoIndexingFileVisitor.getIndex();

    long duration = System.currentTimeMillis() - startTime;
    RepoIndexingStats stats = repoIndexingFileVisitor.indexingStats;
    log.info(
        "Indexing took {} ms. Files visited: {}, source files visited: {}, source roots found: {}",
        duration,
        stats.filesVisited,
        stats.sourceFilesVisited,
        index.sourceRoots.size());
  }

  private static final class RepoIndexingFileVisitor implements FileVisitor<Path> {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexingFileVisitor.class);

    private final SourceRootResolver sourceRootResolver;
    private final ClassNameTrie.Builder trieBuilder;
    private final LinkedHashSet<String> sourceRoots;
    private final RepoIndexingStats indexingStats;
    private final Path repoRoot;

    private RepoIndexingFileVisitor(SourceRootResolver sourceRootResolver, Path repoRoot) {
      this.sourceRootResolver = sourceRootResolver;
      this.repoRoot = repoRoot;
      trieBuilder = new ClassNameTrie.Builder();
      sourceRoots = new LinkedHashSet<>();
      indexingStats = new RepoIndexingStats();
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
      indexingStats.filesVisited++;

      try {
        String fileName = file.getFileName().toString();
        SourceType sourceType = SourceType.getByFileName(fileName);
        if (sourceType != null) {
          indexingStats.sourceFilesVisited++;

          Path currentSourceRoot = sourceRootResolver.getSourceRoot(file);
          sourceRoots.add(repoRoot.relativize(currentSourceRoot).toString());

          Path relativePath = currentSourceRoot.relativize(file);
          String classNameWithExtension = relativePath.toString().replace(File.separatorChar, '.');
          if (!classNameWithExtension.isEmpty()) {
            trieBuilder.put(classNameWithExtension, sourceRoots.size() - 1);
          }
        }
      } catch (Exception e) {
        log.error("Failed to index file {}", file, e);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
      if (exc != null) {
        log.error("Failed to visit file: {}", file, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
      if (exc != null) {
        log.error("Failed to visit directory: {}", dir, exc);
      }
      return FileVisitResult.CONTINUE;
    }

    public RepoIndex getIndex() {
      return new RepoIndex(trieBuilder.buildTrie(), new ArrayList<>(sourceRoots));
    }
  }

  private static final class RepoIndexingStats {
    int filesVisited;
    int sourceFilesVisited;
  }

  private static final class RepoIndex {
    private final ClassNameTrie trie;
    private final List<String> sourceRoots;

    private RepoIndex(ClassNameTrie trie, List<String> sourceRoots) {
      this.trie = trie;
      this.sourceRoots = sourceRoots;
    }
  }

  enum SourceType {
    JAVA(".java"),
    GROOVY(".groovy"),
    KOTLIN(".kt");

    private final String extension;

    SourceType(String extension) {
      this.extension = extension;
    }

    public String getExtension() {
      return extension;
    }

    static SourceType getByFileName(String fileName) {
      for (SourceType sourceType : values()) {
        if (fileName.endsWith(sourceType.extension)) {
          return sourceType;
        }
      }
      return null;
    }
  }

  private static final class SourceFileAttributeVisitor extends ClassVisitor {
    private String source;

    SourceFileAttributeVisitor() {
      super(Opcodes.ASM9);
    }

    @Override
    public void visitSource(String source, String debug) {
      this.source = source;
    }

    public String getSource() {
      return source;
    }
  }
}
