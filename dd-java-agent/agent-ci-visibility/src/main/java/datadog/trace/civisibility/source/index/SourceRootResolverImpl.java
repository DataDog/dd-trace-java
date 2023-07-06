package datadog.trace.civisibility.source.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

class SourceRootResolverImpl implements SourceRootResolver {

  private static final String PACKAGE_KEYWORD = "package";
  private final FileSystem fileSystem;

  SourceRootResolverImpl(FileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  /**
   * Given a path to a Java source file, returns its source root (i.e. path without the filename and
   * package folders).
   *
   * <p>For example, a class <code>foo.bar.MyClass</code> located at <code>
   * /repo/src/foo/bar/MyClass.java</code> will have the source root <code>/repo/src</code>
   *
   * <p>The implementation of this method is rather naive: it does not actually parse the file, nor
   * does it build an AST.
   *
   * <p>It simply looks for a line, that contains the <code>package</code> keyword, extracts the
   * part that goes after it and until the nearest <code>;</code> character, then verifies that the
   * extracted part looks plausible by checking the actual file path (package path is the suffix
   * that is stripped from the full path in order to get the source root).
   */
  @Override
  public Path getSourceRoot(Path sourceFile) throws IOException {
    Path folder = sourceFile.getParent();

    try (BufferedReader br = Files.newBufferedReader(sourceFile)) {
      String line;
      while ((line = br.readLine()) != null) {
        int packageDeclarationStart = line.indexOf(PACKAGE_KEYWORD);
        if (packageDeclarationStart == -1) {
          continue;
        }

        int lineLength = line.length();
        int packageNameStart = packageDeclarationStart + PACKAGE_KEYWORD.length();
        while (packageNameStart < lineLength
            && Character.isWhitespace(line.charAt(packageNameStart))) {
          packageNameStart++;
        }

        int packageNameEnd = line.indexOf(';', packageNameStart);
        if (packageNameEnd == -1) {
          packageNameEnd = lineLength; // no ';' is possible if this is a groovy file
        }

        String packageName = line.substring(packageNameStart, packageNameEnd);
        Path packagePath;
        try {
          packagePath = fileSystem.getPath(packageName.replace('.', File.separatorChar));
        } catch (InvalidPathException e) {
          continue;
        }

        if (!folder.endsWith(packagePath)) {
          continue;
        }

        // remove package path suffix from folder path to get source root
        return folder
            .getRoot()
            .resolve(folder.subpath(0, folder.getNameCount() - packagePath.getNameCount()));
      }
    }

    // apparently there is no package declaration - class is located in the default package
    return folder;
  }
}
