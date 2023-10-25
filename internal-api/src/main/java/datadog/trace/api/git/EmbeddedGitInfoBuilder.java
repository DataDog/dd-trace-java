package datadog.trace.api.git;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddedGitInfoBuilder implements GitInfoBuilder {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedGitInfoBuilder.class);

  private static final String EMBEDDED_GIT_PROPERTIES_FILE_NAME = "git.properties";

  private final String resourceName;

  public EmbeddedGitInfoBuilder() {
    this(EMBEDDED_GIT_PROPERTIES_FILE_NAME);
  }

  EmbeddedGitInfoBuilder(String resourceName) {
    this.resourceName = resourceName;
  }

  @Override
  public GitInfo build(@Nullable String repositoryPath) {
    Properties gitProperties = new Properties();
    try (InputStream is = ClassLoader.getSystemResourceAsStream(resourceName)) {
      if (is != null) {
        gitProperties.load(is);
      } else {
        log.debug("Could not find embedded Git properties resource: {}", resourceName);
      }
    } catch (IOException e) {
      log.error("Error reading embedded Git properties from {}", resourceName, e);
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
