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
    return firstNonNull(
        parseIfExists(ciWorkspace, CODEOWNERS_FILE_NAME),
        parseIfExists(ciWorkspace, ".github", CODEOWNERS_FILE_NAME),
        parseIfExists(ciWorkspace, ".gitlab", CODEOWNERS_FILE_NAME),
        parseIfExists(ciWorkspace, "docs", CODEOWNERS_FILE_NAME));
  }

  private Codeowners firstNonNull(Codeowners... candidates) {
    for (Codeowners candidate : candidates) {
      if (candidate != null) {
        return candidate;
      }
    }
    return Codeowners.EMPTY;
  }

  private Codeowners parseIfExists(String repoRoot, String... relativePath) {
    Path path = Paths.get(repoRoot, relativePath);
    try {
      if (Files.exists(path)) {
        try (Reader reader = Files.newBufferedReader(path)) {
          return Codeowners.parse(repoRoot, reader);
        }
      }
    } catch (IOException e) {
      log.error("Could not read CODEOWNERS file from {}", path, e);
    }
    return null;
  }
}
