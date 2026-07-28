package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleDistributionTest {

  @TempDir Path projectFolder;

  @Test
  void rewriteWrapperDistributionUrlReplacesGeneratedDefaults() throws IOException {
    Path propertiesFile = projectFolder.resolve("gradle/wrapper/gradle-wrapper.properties");
    Files.createDirectories(propertiesFile.getParent());
    Files.write(
        propertiesFile,
        ("distributionBase=GRADLE_USER_HOME\n"
                + "distributionUrl=https\\://old.example/gradle.zip\n"
                + "networkTimeout=10000\n"
                + "retries=0\n"
                + "retryBackOffMs=500\n"
                + "zipStoreBase=GRADLE_USER_HOME\n")
            .getBytes(StandardCharsets.UTF_8));

    GradleDistribution.rewriteWrapperDistributionUrl(projectFolder, "8.14.3");
    GradleDistribution.rewriteWrapperDistributionUrl(projectFolder, "8.14.3");

    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(propertiesFile)) {
      properties.load(input);
    }
    assertEquals(GradleDistribution.uriFor("8.14.3").toString(), properties.get("distributionUrl"));
    assertEquals("30000", properties.get("networkTimeout"));
    assertEquals("2", properties.get("retries"));
    assertEquals("1000", properties.get("retryBackOffMs"));

    List<String> lines = Files.readAllLines(propertiesFile, StandardCharsets.UTF_8);
    assertEquals(1, lines.stream().filter(line -> line.startsWith("distributionUrl=")).count());
    assertEquals(1, lines.stream().filter(line -> line.startsWith("networkTimeout=")).count());
    assertEquals(1, lines.stream().filter(line -> line.startsWith("retries=")).count());
    assertEquals(1, lines.stream().filter(line -> line.startsWith("retryBackOffMs=")).count());
  }
}
