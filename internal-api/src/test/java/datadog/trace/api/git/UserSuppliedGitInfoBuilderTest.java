package datadog.trace.api.git;

import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_BRANCH;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_DATE;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_EMAIL;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_AUTHOR_NAME;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_DATE;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_EMAIL;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_COMMITTER_NAME;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_MESSAGE;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_SHA;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_REPOSITORY_URL;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_TAG;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.util.ConfigStrings.propertyNameToEnvironmentVariableName;
import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName;
import static datadog.trace.util.ConfigStrings.toEnvVar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UserSuppliedGitInfoBuilderTest extends DDJavaSpecification {

  private static final String GIT_TAGS =
      Tags.GIT_REPOSITORY_URL + ":repo_url," + Tags.GIT_COMMIT_SHA + ":commit_sha";

  @Test
  void testNoUserSuppliedGitInfo() {
    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertTrue(gitInfo.isEmpty());
  }

  @ParameterizedTest(name = "user supplied git info: env var {0}")
  @MethodSource("userSuppliedGitInfoArguments")
  void userSuppliedGitInfoEnvVar(
      String envVariable, String value, Function<GitInfo, String> gitInfoValueProvider) {
    injectEnvConfig(propertyNameToEnvironmentVariableName(envVariable), value);

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertEquals(value, gitInfoValueProvider.apply(gitInfo));
  }

  @ParameterizedTest(name = "user supplied git info: system property {0}")
  @MethodSource("userSuppliedGitInfoArguments")
  void userSuppliedGitInfoSystemProperty(
      String systemProperty, String value, Function<GitInfo, String> gitInfoValueProvider) {
    injectSysConfig(propertyNameToSystemPropertyName(systemProperty), value);

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertEquals(value, gitInfoValueProvider.apply(gitInfo));
  }

  static Stream<Arguments> userSuppliedGitInfoArguments() {
    return Stream.of(
        arguments(
            DD_GIT_REPOSITORY_URL,
            "git repo URL",
            (Function<GitInfo, String>) GitInfo::getRepositoryURL),
        arguments(DD_GIT_BRANCH, "git branch", (Function<GitInfo, String>) GitInfo::getBranch),
        arguments(DD_GIT_TAG, "git tag", (Function<GitInfo, String>) GitInfo::getTag),
        arguments(
            DD_GIT_COMMIT_SHA,
            "commit SHA",
            (Function<GitInfo, String>) gitInfo -> gitInfo.getCommit().getSha()),
        arguments(
            DD_GIT_COMMIT_MESSAGE,
            "commit message",
            (Function<GitInfo, String>) gitInfo -> gitInfo.getCommit().getFullMessage()),
        arguments(
            DD_GIT_COMMIT_AUTHOR_NAME,
            "commit author",
            (Function<GitInfo, String>) gitInfo -> gitInfo.getCommit().getAuthor().getName()),
        arguments(
            DD_GIT_COMMIT_AUTHOR_EMAIL,
            "commit author mail",
            (Function<GitInfo, String>) gitInfo -> gitInfo.getCommit().getAuthor().getEmail()),
        arguments(
            DD_GIT_COMMIT_AUTHOR_DATE,
            "2022-12-29T11:38:44.254Z",
            (Function<GitInfo, String>)
                gitInfo -> gitInfo.getCommit().getAuthor().getIso8601Date()),
        arguments(
            DD_GIT_COMMIT_COMMITTER_NAME,
            "committer",
            (Function<GitInfo, String>) gitInfo -> gitInfo.getCommit().getCommitter().getName()),
        arguments(
            DD_GIT_COMMIT_COMMITTER_EMAIL,
            "committer mail",
            (Function<GitInfo, String>) gitInfo -> gitInfo.getCommit().getCommitter().getEmail()),
        arguments(
            DD_GIT_COMMIT_COMMITTER_DATE,
            "2022-12-29T10:38:44.254Z",
            (Function<GitInfo, String>)
                gitInfo -> gitInfo.getCommit().getCommitter().getIso8601Date()));
  }

  @Test
  void branchNameIsNormalized() {
    injectEnvConfig(propertyNameToEnvironmentVariableName(DD_GIT_BRANCH), "origin/myBranch");

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertEquals("myBranch", gitInfo.getBranch());
  }

  @Test
  void tagCanBeSuppliedInBranchVar() {
    injectEnvConfig(propertyNameToEnvironmentVariableName(DD_GIT_BRANCH), "refs/tags/myTag");

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertNull(gitInfo.getBranch());
    assertEquals("myTag", gitInfo.getTag());
  }

  @Test
  void dedicatedTagVarHasPreferenceOverTagSuppliedInsideBranchVar() {
    injectEnvConfig(propertyNameToEnvironmentVariableName(DD_GIT_TAG), "myProvidedTag");
    injectEnvConfig(propertyNameToEnvironmentVariableName(DD_GIT_BRANCH), "refs/tags/myTag");

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertNull(gitInfo.getBranch());
    assertEquals("myProvidedTag", gitInfo.getTag());
  }

  @Test
  void gitInfoIsExtractedFromGlobalTags() {
    injectEnvConfig(toEnvVar(GeneralConfig.TAGS), GIT_TAGS);

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertEquals("repo_url", gitInfo.getRepositoryURL());
    assertEquals("commit_sha", gitInfo.getCommit().getSha());
  }

  @Test
  void globalTagsHaveLowerPriorityThanDedicatedEnvironmentVariables() {
    injectEnvConfig(toEnvVar(GeneralConfig.TAGS), GIT_TAGS);
    injectEnvConfig(
        propertyNameToEnvironmentVariableName(DD_GIT_REPOSITORY_URL), "overridden_repo_url");
    injectEnvConfig(
        propertyNameToEnvironmentVariableName(DD_GIT_COMMIT_SHA), "overridden_commit_sha");

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertEquals("overridden_repo_url", gitInfo.getRepositoryURL());
    assertEquals("overridden_commit_sha", gitInfo.getCommit().getSha());
  }
}
