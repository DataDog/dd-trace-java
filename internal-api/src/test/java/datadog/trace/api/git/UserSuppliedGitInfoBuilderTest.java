package datadog.trace.api.git;

import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_BRANCH;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_COMMIT_SHA;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_REPOSITORY_URL;
import static datadog.trace.api.git.UserSuppliedGitInfoBuilder.DD_GIT_TAG;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.util.ConfigStrings.propertyNameToEnvironmentVariableName;
import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName;
import static datadog.trace.util.ConfigStrings.toEnvVar;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.GeneralConfig;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.test.junit.utils.converter.MethodRefGetterConverter;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class UserSuppliedGitInfoBuilderTest extends DDJavaSpecification {

  private static final String GIT_TAGS =
      Tags.GIT_REPOSITORY_URL + ":repo_url," + Tags.GIT_COMMIT_SHA + ":commit_sha";

  @Test
  void testNoUserSuppliedGitInfo() {
    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertTrue(gitInfo.isEmpty());
  }

  @UserSuppliedGitInfoTable
  void userSuppliedGitInfoEnvVar(
      String envVariable,
      String value,
      @ConvertWith(MethodRefGetterConverter.class) Function<GitInfo, String> gitInfoValueProvider) {
    injectEnvConfig(propertyNameToEnvironmentVariableName(envVariable), value);

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertEquals(value, gitInfoValueProvider.apply(gitInfo));
  }

  @UserSuppliedGitInfoTable
  void userSuppliedGitInfoSystemProperty(
      String systemProperty,
      String value,
      @ConvertWith(MethodRefGetterConverter.class) Function<GitInfo, String> gitInfoValueProvider) {
    injectSysConfig(propertyNameToSystemPropertyName(systemProperty), value);

    GitInfo gitInfo = new UserSuppliedGitInfoBuilder().build(null);

    assertFalse(gitInfo.isEmpty());
    assertEquals(value, gitInfoValueProvider.apply(gitInfo));
  }

  /** Data shared by the env var and system property variants of the user supplied git info test. */
  @Target(METHOD)
  @Retention(RUNTIME)
  @TableTest({
    "scenario               | key                        | value                      | gitInfoValueProvider                            ",
    "repository URL         | git.repository.url         | git repo URL               | GitInfo::getRepositoryURL                       ",
    "branch                 | git.branch                 | git branch                 | GitInfo::getBranch                              ",
    "tag                    | git.tag                    | git tag                    | GitInfo::getTag                                 ",
    "commit SHA             | git.commit.sha             | commit SHA                 | GitInfo::getCommit::getSha                      ",
    "commit message         | git.commit.message         | commit message             | GitInfo::getCommit::getFullMessage              ",
    "commit author name     | git.commit.author.name     | commit author              | GitInfo::getCommit::getAuthor::getName          ",
    "commit author email    | git.commit.author.email    | commit author mail         | GitInfo::getCommit::getAuthor::getEmail         ",
    "commit author date     | git.commit.author.date     | '2022-12-29T11:38:44.254Z' | GitInfo::getCommit::getAuthor::getIso8601Date   ",
    "commit committer name  | git.commit.committer.name  | committer                  | GitInfo::getCommit::getCommitter::getName       ",
    "commit committer email | git.commit.committer.email | committer mail             | GitInfo::getCommit::getCommitter::getEmail      ",
    "commit committer date  | git.commit.committer.date  | '2022-12-29T10:38:44.254Z' | GitInfo::getCommit::getCommitter::getIso8601Date"
  })
  @interface UserSuppliedGitInfoTable {}

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
