package datadog.crashtracking.buildid;

import datadog.environment.OperatingSystem;
import java.nio.file.Path;

/**
 * Interface for extracting build IDs from native library binaries. Build IDs help identify exact
 * library versions for symbolization of native stack traces.
 */
public interface BuildIdExtractor {
  /**
   * Extracts build ID from a binary file.
   *
   * @param file Path to the library file
   * @return Build ID as hex string, or null if not found or on error
   */
  String extractBuildId(Path file);

  /**
   * @return the file type this extractor operates for.
   */
  BuildInfo.FileType fileType();

  /**
   * @return the build id type this extractor is able to provide.
   */
  BuildInfo.BuildIdType buildIdType();

  /**
   * Factory method that returns appropriate extractor for the platform.
   *
   * @return Platform-specific build ID extractor
   */
  static BuildIdExtractor create() {
    if (OperatingSystem.isLinux()) {
      return new ElfBuildIdExtractor();
    } else if (OperatingSystem.isWindows()) {
      return new PeBuildIdExtractor();
    }
    return new NoOpBuildIdExtractor();
  }
}
