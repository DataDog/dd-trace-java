package datadog.smoketest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.util.GradleVersion;
import org.gradle.util.internal.DistributionLocator;
import org.gradle.wrapper.Download;
import org.gradle.wrapper.Install;
import org.gradle.wrapper.Logger;
import org.gradle.wrapper.PathAssembler;
import org.gradle.wrapper.WrapperConfiguration;

final class GradleDistribution {

  static final String GRADLE_INSTALLATION_DIR_ENV = "GRADLE_INSTALLATION_DIR";

  private static final String MASS_READ_URL_ENV = "MASS_READ_URL";

  /** Shared distribution cache; defaults to the Gradle user home. */
  private static final String DISTRIBUTION_CACHE_PROPERTY =
      "datadog.smoketest.gradle.distributionCache";

  // Gradle defaults to a 10s timeout and no retries.
  private static final int NETWORK_TIMEOUT_MS = 30_000;
  private static final int RETRIES = 2;
  private static final int RETRY_BACK_OFF_MS = 1_000;

  private GradleDistribution() {}

  /** Distribution sources, preferring MASS and falling back upstream. */
  static List<URI> urisFor(String gradleVersion) {
    URI upstream =
        new DistributionLocator().getDistributionFor(GradleVersion.version(gradleVersion));
    String massReadUrl = System.getenv(MASS_READ_URL_ENV);
    if (massReadUrl == null || massReadUrl.trim().isEmpty()) {
      return Collections.singletonList(upstream);
    }
    List<URI> uris = new ArrayList<>(2);
    uris.add(massUriFor(massReadUrl, gradleVersion));
    uris.add(upstream);
    return uris;
  }

  /** Preferred source for a distribution: MASS when configured, upstream otherwise. */
  static URI uriFor(String gradleVersion) {
    return urisFor(gradleVersion).get(0);
  }

  /** Installs from each source in order using Gradle's cache and locking. */
  static File install(String gradleVersion, Path projectFolder) {
    Logger logger = new Logger(false);
    Download download =
        new Download(
            logger, "Gradle Tooling API", GradleVersion.current().getVersion(), NETWORK_TIMEOUT_MS);
    Install install =
        new Install(
            logger, download, new PathAssembler(distributionCache(), projectFolder.toFile()));

    for (URI uri : urisFor(gradleVersion)) {
      WrapperConfiguration configuration = new WrapperConfiguration();
      configuration.setDistribution(uri);
      configuration.setNetworkTimeout(NETWORK_TIMEOUT_MS);
      configuration.setRetries(RETRIES);
      configuration.setRetryBackOffMs(RETRY_BACK_OFF_MS);
      try {
        return install.createDist(configuration);
      } catch (Exception e) {
        System.out.println(
            "MASS_FALLBACK gradle-testkit-distribution: could not install Gradle "
                + gradleVersion
                + " from "
                + uri
                + ": "
                + e);
      }
    }
    return null;
  }

  /** Uses the shared install and exposes it to nested TestKit builds. */
  static GradleRunner withDistribution(
      GradleRunner runner,
      String gradleVersion,
      Path projectFolder,
      Map<String, String> environment) {
    File installation = install(gradleVersion, projectFolder);
    if (installation != null) {
      environment.put(GRADLE_INSTALLATION_DIR_ENV, installation.getAbsolutePath());
      return runner.withGradleInstallation(installation);
    }
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

  /** Routes a fixture wrapper through MASS and enables retries. */
  static void rewriteWrapperDistributionUrl(Path projectFolder, String gradleVersion)
      throws IOException {
    Path wrapperProperties = projectFolder.resolve("gradle/wrapper/gradle-wrapper.properties");
    String contents = new String(Files.readAllBytes(wrapperProperties), StandardCharsets.UTF_8);
    String updated = setProperty(contents, "distributionUrl", uriPropertiesValueFor(gradleVersion));
    updated = setProperty(updated, "networkTimeout", Integer.toString(NETWORK_TIMEOUT_MS));
    updated = setProperty(updated, "retries", Integer.toString(RETRIES));
    updated = setProperty(updated, "retryBackOffMs", Integer.toString(RETRY_BACK_OFF_MS));
    Files.write(wrapperProperties, updated.getBytes(StandardCharsets.UTF_8));
  }

  private static String setProperty(String contents, String name, String value) {
    String newline = contents.contains("\r\n") ? "\r\n" : "\n";
    Pattern line = Pattern.compile("(?m)^" + Pattern.quote(name) + "=.*(?:\\r?\\n|$)");
    String withoutProperty = line.matcher(contents).replaceAll("");
    if (!withoutProperty.isEmpty() && !withoutProperty.endsWith("\n")) {
      withoutProperty += newline;
    }
    return withoutProperty + name + "=" + value + newline;
  }

  private static File distributionCache() {
    String configured = System.getProperty(DISTRIBUTION_CACHE_PROPERTY);
    if (configured != null && !configured.trim().isEmpty()) {
      return Paths.get(configured).toFile();
    }
    return Paths.get(System.getProperty("user.home"), ".gradle").toFile();
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
