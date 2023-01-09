package datadog.trace.bootstrap.instrumentation.ci.codeowners;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeownersProvider {

  private static final Logger log = LoggerFactory.getLogger(CodeownersProvider.class);

  private static final String CODEOWNERS_FILE_NAME = "CODEOWNERS";

  public Codeowners build(String ciWorkspace) {
    return find(
        ciWorkspace,
        Paths.get(CODEOWNERS_FILE_NAME),
        Paths.get(".github", CODEOWNERS_FILE_NAME),
        Paths.get(".gitlab", CODEOWNERS_FILE_NAME),
        Paths.get("docs", CODEOWNERS_FILE_NAME));
  }

  private Codeowners find(String repoRoot, Path... possibleRelativePaths) {
    Path rootPath = Paths.get(repoRoot);
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
