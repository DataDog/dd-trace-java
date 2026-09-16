package datadog.crashtracking.buildid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;

/**
 * Tests for BuildIdExtractor implementations using synthetic minimal fixture files committed to
 * test resources. Fixtures cover ELF64/ELF32, little/big-endian, and PE32/PE32+ formats.
 */
public class BuildIdExtractorIntegrationTest {
  private static final Logger logger =
      LoggerFactory.getLogger(BuildIdExtractorIntegrationTest.class);

  @TableTest({
    "scenario            | resource                | expectedBuildId                         ",
    "ELF64 little-endian | buildid/elf/elf64-le.so | 0123456789abcdef0123456789abcdefdeadbeef",
    "ELF32 little-endian | buildid/elf/elf32-le.so | deadbeefcafebabe123456789abcdef0        ",
    "ELF64 big-endian    | buildid/elf/elf64-be.so | ff00112233445566778899aabbccddeeff000102"
  })
  void testElfBuildIdExtraction(String resource, String expectedBuildId) throws Exception {
    Path file = Paths.get(getClass().getClassLoader().getResource(resource).toURI());

    ElfBuildIdExtractor extractor = new ElfBuildIdExtractor();
    String buildId = extractor.extractBuildId(file);

    logger.info("Found build ID: {} for {}", buildId, resource);

    assertNotNull(buildId, "Build ID should be found for " + resource);
    assertEquals(expectedBuildId, buildId, "Build ID mismatch for " + resource);
    assertEquals(BuildInfo.FileType.ELF, extractor.fileType());
    assertEquals(BuildInfo.BuildIdType.GNU, extractor.buildIdType());
  }

  @TableTest({
    "scenario                                         | resource                             | expectedBuildId                   ",
    "PE32+ single debug entry                         | buildid/pe/pe32plus-single-entry.dll | 12345678ABCDEF0123456789ABCDEF011 ",
    "PE32 single debug entry                          | buildid/pe/pe32-single-entry.dll     | DEADBEEF12345678DEADBEEF123456782 ",
    "PE32+ multiple debug entries, CodeView is second | buildid/pe/pe32plus-multi-entry.dll  | CAFEBABEBEEFDEADCAFEBABEBEEFDEADff"
  })
  void testPeBuildIdExtraction(String resource, String expectedBuildId) throws Exception {
    Path file = Paths.get(getClass().getClassLoader().getResource(resource).toURI());

    PeBuildIdExtractor extractor = new PeBuildIdExtractor();
    String buildId = extractor.extractBuildId(file);

    logger.info("Found build ID: {} for {}", buildId, resource);

    assertNotNull(buildId, "Build ID should be found for " + resource);
    assertEquals(expectedBuildId, buildId, "Build ID mismatch for " + resource);
    assertEquals(BuildInfo.FileType.PE, extractor.fileType());
    assertEquals(BuildInfo.BuildIdType.PDB, extractor.buildIdType());
  }
}
