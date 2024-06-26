package datadog.trace.civisibility.codeowners;

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

  public Codeowners build(String repoRoot) {
    return find(
        fileSystem.getPath(repoRoot, CODEOWNERS_FILE_NAME),
        fileSystem.getPath(repoRoot, ".github", CODEOWNERS_FILE_NAME),
        fileSystem.getPath(repoRoot, ".gitlab", CODEOWNERS_FILE_NAME),
        fileSystem.getPath(repoRoot, "docs", CODEOWNERS_FILE_NAME));
  }

  private Codeowners find(Path... possiblePaths) {
    for (Path path : possiblePaths) {
      try {
        if (Files.exists(path)) {
          try (Reader reader = Files.newBufferedReader(path)) {
            return CodeownersImpl.parse(reader);
          }
        }
      } catch (IOException e) {
        log.error("Could not read CODEOWNERS file from {}", path, e);
      }
    }
    return NoCodeowners.INSTANCE;
  }
}
