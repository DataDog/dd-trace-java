package datadog.trace.api.git;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmbeddedGitInfoBuilderTest {

  @Test
  void testNoEmbeddedGitInfo() {
    GitInfo gitInfo =
        new EmbeddedGitInfoBuilder(singletonList("non-existent-git.properties")).build(null);

    assertTrue(gitInfo.isEmpty());
  }

  @Test
  void testMavenPluginGeneratedGitInfo() {
    String mavenGitProperties = "datadog/trace/bootstrap/git/maven-git.properties";
    GitInfo gitInfo = new EmbeddedGitInfoBuilder(singletonList(mavenGitProperties)).build(null);

    assertEquals("git@github.com:DataDog/ciapp-test-resources.git", gitInfo.getRepositoryURL());
    assertEquals("master", gitInfo.getBranch());
    assertEquals("test_tag", gitInfo.getTag());
    CommitInfo commit = gitInfo.getCommit();
    assertEquals("847416a0f14356609ef2a2e0bf19b8a7222c250a", commit.getSha());
    assertEquals("Add integration test in Java Gradle JUnit 5 project", commit.getFullMessage());
    PersonInfo author = commit.getAuthor();
    assertEquals("Nikita Tkachenko", author.getName());
    assertEquals("nikita.tkachenko@datadoghq.com", author.getEmail());
    assertEquals("2023-03-22T14:43:22+0100", author.getIso8601Date());
    PersonInfo committer = commit.getCommitter();
    assertEquals("Nikita Tkachenko", committer.getName());
    assertEquals("nikita.tkachenko@datadoghq.com", committer.getEmail());
    assertEquals("2023-03-22T14:43:23+0100", committer.getIso8601Date());
  }

  @Test
  void testGradlePluginGeneratedGitInfo() {
    String gradleGitProperties = "datadog/trace/bootstrap/git/gradle-git.properties";
    GitInfo gitInfo = new EmbeddedGitInfoBuilder(singletonList(gradleGitProperties)).build(null);

    assertEquals("git@github.com:DataDog/ciapp-test-resources.git", gitInfo.getRepositoryURL());
    assertEquals("master", gitInfo.getBranch());
    assertEquals("test_tag", gitInfo.getTag());
    CommitInfo commit = gitInfo.getCommit();
    assertEquals("847416a0f14356609ef2a2e0bf19b8a7222c250a", commit.getSha());
    assertEquals("Add integration test in Java Gradle JUnit 5 project\n", commit.getFullMessage());
    PersonInfo author = commit.getAuthor();
    assertEquals("Nikita Tkachenko", author.getName());
    assertEquals("nikita.tkachenko@datadoghq.com", author.getEmail());
    assertEquals("2023-03-22T14:43:21+0100", author.getIso8601Date());
    PersonInfo committer = commit.getCommitter();
    assertEquals("Nikita Tkachenko", committer.getName());
    assertEquals("nikita.tkachenko@datadoghq.com", committer.getEmail());
    assertEquals("2023-03-22T14:43:21+0100", committer.getIso8601Date());
  }

  @Test
  void testEmbeddedGitInfoHasALowerPriorityThanUserSuppliedGitInfo() {
    EmbeddedGitInfoBuilder embeddedGitInfoBuilder = new EmbeddedGitInfoBuilder();
    UserSuppliedGitInfoBuilder userSuppliedGitInfoBuilder = new UserSuppliedGitInfoBuilder();

    assertTrue(embeddedGitInfoBuilder.order() > userSuppliedGitInfoBuilder.order());
  }
}
