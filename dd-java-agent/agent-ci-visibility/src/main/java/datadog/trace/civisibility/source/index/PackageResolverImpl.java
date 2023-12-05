package datadog.trace.civisibility.source.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

public class PackageResolverImpl implements PackageResolver {

  private static final String PACKAGE_KEYWORD = "package";
  private final FileSystem fileSystem;

  public PackageResolverImpl(FileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  /**
   * Given a path to a source file, returns its package path.
   *
   * <p>The implementation of this method is rather naive: it does not actually parse the file, nor
   * does it build an AST.
   *
   * <p>It simply looks for a line, that contains the <code>package</code> keyword and extracts the
   * part that goes after it and until the nearest <code>;</code> character, then verifies that the
   * extracted part looks plausible by checking the actual file path.
   */
  @Override
  public Path getPackage(Path sourceFile) throws IOException {
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
          packageNameEnd = lineLength; // no ';' is possible if this is a Groovy file
        }

        String packageName = line.substring(packageNameStart, packageNameEnd);
        Path packagePath;
        try {
          packagePath = fileSystem.getPath(packageName.replace('.', File.separatorChar));
        } catch (InvalidPathException e) {
          continue;
        }

        if (folder.endsWith(packagePath)) {
          return packagePath;
        }
      }
    }

    // apparently there is no package declaration - class is located in the default package
    return fileSystem.getPath("");
  }
}
