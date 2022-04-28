package datadog.trace.agent.tooling.matchercache;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MatcherCacheFileBuilderParamsTest {
  TestAppender testAppender;

  @BeforeEach
  public void installTestAppender() {
    testAppender = TestAppender.installTestAppender();
  }

  @Test
  public void testParseEmpty() {
    MatcherCacheFileBuilderParams params = MatcherCacheFileBuilderParams.parseArgs();

    assertNull(params.getOutputFile());
    assertTrue(params.getClassPaths().isEmpty());
  }

  @Test
  public void testParseHappyPath() {
    MatcherCacheFileBuilderParams params =
        MatcherCacheFileBuilderParams.parseArgs("-cp", "/tmp", "-o", "./out.bin", "-cp", ".");

    assertEquals("./out.bin", params.getOutputFile());
    assertEquals("[/tmp, .]", params.getClassPaths().toString());
  }

  @Test
  public void testParseInvalidArg() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MatcherCacheFileBuilderParams.parseArgs("-invalid-arg"),
        "-invalid-arg");
  }

  @Test
  public void testParseInvalidArgAmongValidArgs() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MatcherCacheFileBuilderParams.parseArgs("-o", "out.bin", "-invarg", "-cp", "."),
        "-invarg");
  }

  @Test
  public void testValidateEmpty() {
    MatcherCacheFileBuilderParams params = MatcherCacheFileBuilderParams.parseArgs();

    assertFalse(params.validate());
    assertEquals(
        "[ERROR] Mandatory output file path (-o) parameter is missing\n"
            + "[WARN] The classpath to search for classes is not specified. Only JDK and dd-java-tracer classes will be scanned.\n",
        testAppender.toString());
  }

  @Test
  public void testValidateHappyPath() throws IOException {
    File outFile = File.createTempFile("out", ".mc");
    File jarFile = File.createTempFile("whatever", ".jar");
    File classFolder = Files.createTempDirectory("classes").toFile();

    assertTrue(outFile.delete());
    MatcherCacheFileBuilderParams params =
        MatcherCacheFileBuilderParams.parseArgs(
            "-o", outFile.toString(), "-cp", jarFile.toString(), "-cp", classFolder.toString());

    assertTrue(params.validate());
    assertEquals("", testAppender.toString());
  }

  @Test
  public void testValidateOutputFileAlreadyExists() throws IOException {
    File outFile = File.createTempFile("out", ".mc");
    MatcherCacheFileBuilderParams params =
        MatcherCacheFileBuilderParams.parseArgs("-o", outFile.toString(), "-cp", ".");

    assertTrue(params.validate());
    assertEquals(
        "[WARN] File " + outFile + " already exists and will be replaced\n",
        testAppender.toString());
  }

  @Test
  public void testValidateMissingOutputFolder() throws IOException {
    File tmpFolder = Files.createTempDirectory("tmp").toFile();
    File outFile = new File(tmpFolder, "missing-folder/out.mc");

    MatcherCacheFileBuilderParams params =
        MatcherCacheFileBuilderParams.parseArgs("-o", outFile.toString(), "-cp", ".");

    assertFalse(params.validate());
    assertEquals(
        "[ERROR] Output folder " + outFile.getParentFile().toString() + " doesn't exist\n",
        testAppender.toString());
  }

  @Test
  public void testValidateMissingClassPaths() throws IOException {
    File outFile = File.createTempFile("out", null);
    File jarFile = File.createTempFile("some", ".jar");
    File tmpFolder = Files.createTempDirectory("tmp").toFile();

    assertTrue(outFile.delete());
    assertTrue(jarFile.delete());
    assertTrue(tmpFolder.delete());
    MatcherCacheFileBuilderParams params =
        MatcherCacheFileBuilderParams.parseArgs(
            "-o", outFile.toString(), "-cp", jarFile.toString(), "-cp", tmpFolder.toString());

    assertFalse(params.validate());
    assertEquals(
        "[ERROR] Class path "
            + jarFile
            + " doesn't exist\n"
            + "[ERROR] Class path "
            + tmpFolder
            + " doesn't exist\n",
        testAppender.toString());
  }
}
