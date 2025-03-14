package datadog.trace.api.git;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddedGitInfoBuilder implements GitInfoBuilder {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedGitInfoBuilder.class);

  // Spring boot fat jars and wars should have the git.properties file in a specific path,
  // guaranteeing that it's not coming from a dependency
  private static final String SPRING_BOOT_JAR_EMBEDDED_DATADOG_GIT_PROPERTIES_FILE_NAME =
      "BOOT-INF/classes/datadog_git.properties";
  private static final String SPRING_BOOT_JAR_EMBEDDED_GIT_PROPERTIES_FILE_NAME =
      "BOOT-INF/classes/git.properties";
  private static final String WAR_EMBEDDED_DATADOG_GIT_PROPERTIES_FILE_NAME =
      "WEB-INF/classes/datadog_git.properties";
  private static final String WAR_EMBEDDED_GIT_PROPERTIES_FILE_NAME =
      "WEB-INF/classes/git.properties";
  // If we can't find the files above, probably because we're not in a spring context, we can look
  // at the root of the classpath.
  // Since it could be tainted by dependencies, we're looking for a specific datadog_git.properties
  // file.
  private static final String EMBEDDED_DATADOOG_GIT_PROPERTIES_FILE_NAME = "datadog_git.properties";

  private final List<String> resourceNames;

  public EmbeddedGitInfoBuilder() {
    // Order is important here, from the most reliable sources to the least reliable ones
    this(
        Arrays.asList(
            SPRING_BOOT_JAR_EMBEDDED_DATADOG_GIT_PROPERTIES_FILE_NAME,
            SPRING_BOOT_JAR_EMBEDDED_GIT_PROPERTIES_FILE_NAME,
            WAR_EMBEDDED_DATADOG_GIT_PROPERTIES_FILE_NAME,
            WAR_EMBEDDED_GIT_PROPERTIES_FILE_NAME,
            EMBEDDED_DATADOOG_GIT_PROPERTIES_FILE_NAME));
  }

  EmbeddedGitInfoBuilder(List<String> resourceNames) {
    this.resourceNames = resourceNames;
  }

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    Properties gitProperties = new Properties();

    for (String resourceName : resourceNames) {
      try (InputStream is = ClassLoader.getSystemResourceAsStream(resourceName)) {
        if (is != null) {
          gitProperties.load(is);
          // stop at the first git properties file found
          break;
        } else {
          log.debug("Could not find embedded Git properties resource: {}", resourceName);
        }
      } catch (IOException e) {
        log.error("Error reading embedded Git properties from {}", resourceName, e);
      }
    }

    String commitSha = gitProperties.getProperty("git.commit.id");
    if (commitSha == null) {
      commitSha = gitProperties.getProperty("git.commit.id.full");
    }

    String committerTime = gitProperties.getProperty("git.commit.committer.time");
    if (committerTime == null) {
      committerTime = gitProperties.getProperty("git.commit.time");
    }

    String authorTime = gitProperties.getProperty("git.commit.author.time");
    if (authorTime == null) {
      authorTime = gitProperties.getProperty("git.commit.time");
    }

    return new GitInfo(
        gitProperties.getProperty("git.remote.origin.url"),
        gitProperties.getProperty("git.branch"),
        gitProperties.getProperty("git.tags"),
        new CommitInfo(
            commitSha,
            new PersonInfo(
                gitProperties.getProperty("git.commit.user.name"),
                gitProperties.getProperty("git.commit.user.email"),
                authorTime),
            new PersonInfo(
                gitProperties.getProperty("git.commit.user.name"),
                gitProperties.getProperty("git.commit.user.email"),
                committerTime),
            gitProperties.getProperty("git.commit.message.full")));
  }

  @Override
  public int order() {
    return Integer.MAX_VALUE;
  }
}
