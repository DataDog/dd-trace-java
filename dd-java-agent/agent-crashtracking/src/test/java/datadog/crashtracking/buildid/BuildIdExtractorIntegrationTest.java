package datadog.crashtracking.buildid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration tests for BuildIdExtractor implementations using Docker containers. Tests validate
 * build ID extraction from real ELF (Unix/Linux) and PE (Windows) binaries.
 */
public class BuildIdExtractorIntegrationTest {
  private static final String DOTNET_SDK_MAJOR_VERSION = "8.0";
  private static GenericContainer<?> linuxContainer;
  private static GenericContainer<?> dotnetContainer;
  @TempDir private static Path tempDir;
  private static final Logger logger =
      LoggerFactory.getLogger(BuildIdExtractorIntegrationTest.class);
  private static String dotnetVersion;

  @BeforeAll
  static void startContainers() throws IOException {
    // Start Ubuntu container for ELF testing
    // Use linux/amd64 platform to ensure x86_64 binaries are available
    linuxContainer =
        new GenericContainer<>(
                DockerImageName.parse("ubuntu:22.04").asCompatibleSubstituteFor("ubuntu"))
            .withCommand("sleep", "infinity")
            .withStartupTimeout(Duration.ofMinutes(2));
    linuxContainer.start();

    // Start dotnet SDK container for PE testing
    // Use linux/amd64 platform to ensure consistent binary format
    dotnetContainer =
        new GenericContainer<>(
                DockerImageName.parse("mcr.microsoft.com/dotnet/sdk:" + DOTNET_SDK_MAJOR_VERSION)
                    .asCompatibleSubstituteFor("dotnet"))
            .withCommand("sleep", "infinity")
            .withStartupTimeout(Duration.ofMinutes(2));
    dotnetContainer.start();

    dotnetVersion = discoverExactDotnetVersionFrom(dotnetContainer);
  }

  /**
   * Discovers the .NET Core runtime version installed in the container by listing available
   * versions.
   *
   * @param dotnetContainer the dotNet SDK container
   * @return the discovered .NET version string
   */
  private static String discoverExactDotnetVersionFrom(GenericContainer<?> dotnetContainer)
      throws IOException {
    try {
      ExecResult execResult =
          dotnetContainer.execInContainer("ls", "/usr/share/dotnet/shared/Microsoft.NETCore.App");
      if (execResult.getExitCode() == 0) {
        Pattern pattern =
            Pattern.compile(
                "^" + Pattern.quote(DOTNET_SDK_MAJOR_VERSION) + "\\.\\d+$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(execResult.getStdout());
        if (matcher.find()) {
          return matcher.group();
        }
        throw new IOException(
            "No .NET " + DOTNET_SDK_MAJOR_VERSION + " version found in container");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while discovering .NET version", e);
    }

    throw new IOException("Failed to list .NET versions in container");
  }

  @AfterAll
  static void stopContainers() throws IOException {
    // Stop containers
    if (linuxContainer != null) {
      linuxContainer.stop();
    }
    if (dotnetContainer != null) {
      dotnetContainer.stop();
    }
  }

  /**
   * Copy a binary file from a container to the local temp directory.
   *
   * @param container The container to copy from
   * @param containerPath Path to the binary inside the container
   * @return Path to the copied binary in the local temp directory
   */
  private Path copyFromContainer(GenericContainer<?> container, String containerPath)
      throws IOException {
    String filename = Paths.get(containerPath).getFileName().toString();
    Path localPath = tempDir.resolve(filename + "-" + System.nanoTime());

    container.copyFileFromContainer(containerPath, localPath.toString());

    if (!Files.exists(localPath) || Files.size(localPath) == 0) {
      throw new IOException("Failed to copy binary: " + containerPath);
    }

    return localPath;
  }

  private static Stream<Arguments> elfBinaries() {
    return Stream.of(
        Arguments.of("/lib/x86_64-linux-gnu/libc.so.6", "GNU C Library"),
        Arguments.of("/lib/x86_64-linux-gnu/libm.so.6", "Math library"),
        Arguments.of("/lib/x86_64-linux-gnu/libpthread.so.0", "POSIX threads library"));
  }

  @ParameterizedTest(name = "ELF: {1}")
  @MethodSource("elfBinaries")
  void testElfBuildIdExtraction(String containerPath, String description) throws Exception {
    Path localBinary = copyFromContainer(linuxContainer, containerPath);

    ElfBuildIdExtractor extractor = new ElfBuildIdExtractor();
    String buildId = extractor.extractBuildId(localBinary);

    logger.info("Found build ID: {} for library {}", buildId, localBinary);

    assertNotNull(buildId, "Build ID should be found for " + description);
    assertTrue(
        buildId.matches("^[0-9a-f]{32,40}$"), "Build ID should be 32-40 hex chars: " + buildId);
    assertEquals(BuildInfo.FileType.ELF, extractor.fileType());
    assertEquals(BuildInfo.BuildIdType.GNU, extractor.buildIdType());
  }

  private static Stream<Arguments> peBinaries() {
    String basePath = "/usr/share/dotnet/shared/Microsoft.NETCore.App/" + dotnetVersion;
    return Stream.of(
        Arguments.of(basePath + "/System.Private.CoreLib.dll", "Core .NET Library"),
        Arguments.of(basePath + "/System.Runtime.dll", ".NET Runtime"),
        Arguments.of(basePath + "/System.Console.dll", "Console Library"),
        Arguments.of(basePath + "/Microsoft.CSharp.dll", "C# Compiler Library"));
  }

  @ParameterizedTest(name = "PE: {1}")
  @MethodSource("peBinaries")
  void testPeBuildIdExtraction(String containerPath, String description) throws Exception {
    Path localBinary = copyFromContainer(dotnetContainer, containerPath);

    PeBuildIdExtractor extractor = new PeBuildIdExtractor();
    String buildId = extractor.extractBuildId(localBinary);

    logger.info("Found build ID: {} for library {}", buildId, localBinary);

    assertNotNull(buildId, "GUID+Age should be found for " + description);

    // Build ID format: GUID (32 uppercase hex chars) + Age (lowercase hex, variable length)
    assertTrue(
        buildId.length() >= 33,
        "Build ID should be at least 33 hex chars (GUID + Age): " + buildId);

    // Verify format: GUID part (32 chars) should be uppercase, Age part should be lowercase
    String guidPart = buildId.substring(0, 32);
    String agePart = buildId.substring(32);

    assertTrue(
        guidPart.matches("^[0-9A-F]{32}$"),
        "GUID part (first 32 chars) should be uppercase hex: " + guidPart);
    assertTrue(
        agePart.matches("^[0-9a-f]+$"),
        "Age part (remaining chars) should be lowercase hex: " + agePart);

    // Verify both parts parse correctly as hex
    try {
      new java.math.BigInteger(guidPart, 16);
      Long.parseLong(agePart, 16);
    } catch (NumberFormatException e) {
      throw new AssertionError("Build ID should be valid hex: " + buildId, e);
    }

    logger.info("Build ID format verified: GUID={}, Age={}", guidPart, agePart);

    assertEquals(BuildInfo.FileType.PE, extractor.fileType());
    assertEquals(BuildInfo.BuildIdType.PDB, extractor.buildIdType());
  }
}
