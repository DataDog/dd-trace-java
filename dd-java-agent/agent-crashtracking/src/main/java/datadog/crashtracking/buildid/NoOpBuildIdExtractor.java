package datadog.crashtracking.buildid;

import java.nio.file.Path;

/**
 * No-op build ID extractor for unsupported platforms. Always returns null to indicate build IDs are
 * not available.
 */
public class NoOpBuildIdExtractor implements BuildIdExtractor {
  @Override
  public String extractBuildId(Path file) {
    // No build ID on this platform
    return null;
  }

  @Override
  public BuildInfo.FileType fileType() {
    return null;
  }

  @Override
  public BuildInfo.BuildIdType buildIdType() {
    return null;
  }
}
