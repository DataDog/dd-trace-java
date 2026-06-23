package datadog.smoketest;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.util.GradleVersion;
import org.gradle.util.internal.DistributionLocator;

final class GradleDistribution {

  static final String GRADLE_DISTRIBUTION_URL_ENV = "GRADLE_DISTRIBUTION_URL";

  private static final String MASS_READ_URL_ENV = "MASS_READ_URL";
  private static final Pattern DISTRIBUTION_URL_LINE = Pattern.compile("(?m)^distributionUrl=.*$");

  private GradleDistribution() {}

  static URI uriFor(String gradleVersion) {
    String massReadUrl = System.getenv(MASS_READ_URL_ENV);
    if (massReadUrl == null || massReadUrl.trim().isEmpty()) {
      return new DistributionLocator().getDistributionFor(GradleVersion.version(gradleVersion));
    }
    return massUriFor(massReadUrl, gradleVersion);
  }

  static GradleRunner withDistribution(GradleRunner runner, String gradleVersion) {
    String massReadUrl = System.getenv(MASS_READ_URL_ENV);
    if (massReadUrl == null || massReadUrl.trim().isEmpty()) {
      return runner.withGradleVersion(gradleVersion);
    }
    return runner.withGradleDistribution(massUriFor(massReadUrl, gradleVersion));
  }

  static void propagateMassReadUrl(Map<String, String> environment) {
    String massReadUrl = System.getenv(MASS_READ_URL_ENV);
    if (massReadUrl != null && !massReadUrl.trim().isEmpty()) {
      environment.put(MASS_READ_URL_ENV, massReadUrl);
    }
  }

  static String uriPropertiesValueFor(String gradleVersion) {
    return uriFor(gradleVersion).toString().replace(":", "\\:");
  }

  static void rewriteWrapperDistributionUrl(Path projectFolder, String gradleVersion)
      throws IOException {
    Path wrapperProperties = projectFolder.resolve("gradle/wrapper/gradle-wrapper.properties");
    String contents = new String(Files.readAllBytes(wrapperProperties), StandardCharsets.UTF_8);
    String replacement = "distributionUrl=" + uriPropertiesValueFor(gradleVersion);
    String updated =
        DISTRIBUTION_URL_LINE.matcher(contents).replaceFirst(Matcher.quoteReplacement(replacement));
    Files.write(wrapperProperties, updated.getBytes(StandardCharsets.UTF_8));
  }

  private static URI massUriFor(String massReadUrl, String gradleVersion) {
    String baseUrl = massReadUrl.endsWith("/") ? massReadUrl : massReadUrl + "/";
    return URI.create(
        baseUrl
            + "internal/artifact/services.gradle.org/distributions/gradle-"
            + gradleVersion
            + "-bin.zip");
  }
}
