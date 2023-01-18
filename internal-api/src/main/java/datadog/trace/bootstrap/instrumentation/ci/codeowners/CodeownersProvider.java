package datadog.trace.bootstrap.instrumentation.ci.codeowners;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeownersProvider {

  private static final Logger log = LoggerFactory.getLogger(CodeownersProvider.class);

  static final String CODEOWNERS_FILE_NAME = "CODEOWNERS";

  private final FileSystem fileSystem;

  public CodeownersProvider() {
    this(FileSystems.getDefault());
  }

  CodeownersProvider(FileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  public Codeowners build(String ciWorkspace) {
    return find(
        ciWorkspace,
        fileSystem.getPath(CODEOWNERS_FILE_NAME),
        fileSystem.getPath(".github", CODEOWNERS_FILE_NAME),
        fileSystem.getPath(".gitlab", CODEOWNERS_FILE_NAME),
        fileSystem.getPath("docs", CODEOWNERS_FILE_NAME));
  }

  private Codeowners find(String repoRoot, Path... possibleRelativePaths) {
    Path rootPath = fileSystem.getPath(repoRoot);
    for (Path relativePath : possibleRelativePaths) {
      Path path = rootPath.resolve(relativePath);
      try {
        if (Files.exists(path)) {
          try (Reader reader = Files.newBufferedReader(path)) {
            return Codeowners.parse(repoRoot, reader);
          }
        }
      } catch (IOException e) {
        log.error("Could not read CODEOWNERS file from {}", path, e);
      }
    }
    return Codeowners.EMPTY;
  }
}
