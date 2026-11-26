package datadog.trace.civisibility.ci;

import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_PR_HEAD_COMMIT;
import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_PR_NUMBER;
import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_REPO_COMMIT;
import static datadog.trace.civisibility.ci.AppVeyorInfo.APPVEYOR_REPO_PROVIDER;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.civisibility.ci.env.CiEnvironment;
import datadog.trace.civisibility.ci.env.CiEnvironmentImpl;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.tabletest.junit.TableTest;

class MergeCommitShaTest {
  private static final String SHA = "abcdef0123456789abcdef0123456789abcdef01";
  private static final String APPVEYOR_BUILD_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

  @TableTest({
    "Scenario        | Provider | Values                                                                                        | Expected",
    "github merge    | github   | [GITHUB_REF: refs/pull/42/merge, GITHUB_SHA: SHA]                                             | true    ",
    "github head     | github   | [GITHUB_REF: refs/pull/42/head, GITHUB_SHA: SHA]                                              | false   ",
    "github branch   | github   | [GITHUB_REF: refs/heads/main, GITHUB_SHA: SHA]                                                | false   ",
    "invalid sha     | github   | [GITHUB_REF: refs/pull/42/merge, GITHUB_SHA: short]                                           | false   ",
    "gitlab result   | gitlab   | [CI_MERGE_REQUEST_IID: 42, CI_MERGE_REQUEST_EVENT_TYPE: merged_result, CI_COMMIT_SHA: SHA]    | true    ",
    "gitlab train    | gitlab   | [CI_MERGE_REQUEST_IID: 42, CI_MERGE_REQUEST_EVENT_TYPE: merge_train, CI_COMMIT_SHA: SHA]      | true    ",
    "gitlab detached | gitlab   | [CI_MERGE_REQUEST_IID: 42, CI_MERGE_REQUEST_EVENT_TYPE: detached, CI_COMMIT_SHA: SHA]         | false   ",
    "gitlab no MR    | gitlab   | [CI_MERGE_REQUEST_EVENT_TYPE: merged_result, CI_COMMIT_SHA: SHA]                              | false   ",
    "azure merge     | azure    | [BUILD_REASON: PullRequest, BUILD_SOURCEBRANCH: refs/pull/42/merge, BUILD_SOURCEVERSION: SHA] | true    ",
    "azure branch    | azure    | [BUILD_REASON: PullRequest, BUILD_SOURCEBRANCH: refs/heads/main, BUILD_SOURCEVERSION: SHA]    | false   ",
    "azure manual    | azure    | [BUILD_REASON: Manual, BUILD_SOURCEBRANCH: refs/pull/42/merge, BUILD_SOURCEVERSION: SHA]      | false   ",
    "travis merge    | travis   | [TRAVIS_EVENT_TYPE: pull_request, TRAVIS_PULL_REQUEST: 42, TRAVIS_COMMIT: SHA]                | true    ",
    "travis push     | travis   | [TRAVIS_EVENT_TYPE: push, TRAVIS_PULL_REQUEST: 42, TRAVIS_COMMIT: SHA]                        | false   ",
    "travis no PR    | travis   | [TRAVIS_EVENT_TYPE: pull_request, TRAVIS_PULL_REQUEST: false, TRAVIS_COMMIT: SHA]             | false   ",
    "other provider  | jenkins  | [GITHUB_REF: refs/pull/42/merge, GITHUB_SHA: SHA]                                             | false   "
  })
  void reportsOnlyProviderMergeRevisions(
      String provider, Map<String, String> values, boolean expected) {
    Map<String, String> env = new HashMap<>(values);
    env.replaceAll((key, value) -> "SHA".equals(value) ? SHA.toUpperCase(Locale.ROOT) : value);
    assertEquals(
        expected ? SHA : null,
        providerInfo(provider, new CiEnvironmentImpl(env)).getMergeCommitSha(),
        env::toString);
  }

  @TableTest({
    "Scenario                    | repoProvider | number | head    | build   | merge",
    "GitHub PR without branch    | github       | 42     | head    | build   | true ",
    "non-GitHub repository       | bitbucket    | 42     | head    | build   | false",
    "missing provider            |              | 42     | head    | build   | false",
    "no PR number                | github       |        | head    | build   | false",
    "non-numeric PR number       | github       | false  | head    | build   | false",
    "missing PR head             | github       | 42     |         | build   | false",
    "abbreviated PR head         | github       | 42     | ABCDEF0 | build   | false",
    "invalid PR head             | github       | 42     | invalid | build   | false",
    "same revision               | github       | 42     | head    | head    | false",
    "same revision ignoring case | github       | 42     | head    | upper   | false",
    "missing build SHA           | github       | 42     | head    |         | false",
    "invalid build SHA           | github       | 42     | head    | invalid | false"
  })
  void appveyorRequiresDistinctFullPrAndBuildRevisions(
      String repoProvider, String number, String head, String build, boolean merge) {
    Map<String, String> values = new HashMap<>();
    values.put(APPVEYOR_REPO_PROVIDER, repoProvider);
    values.put(APPVEYOR_PR_NUMBER, number);
    values.put(APPVEYOR_PR_HEAD_COMMIT, appveyorSha(head));
    values.put(APPVEYOR_REPO_COMMIT, appveyorSha(build));
    assertEquals(
        merge ? APPVEYOR_BUILD_SHA : null,
        new AppVeyorInfo(new CiEnvironmentImpl(values)).getMergeCommitSha());
  }

  private static CIProviderInfo providerInfo(String provider, CiEnvironment environment) {
    switch (provider) {
      case "github":
        return new GithubActionsInfo(environment);
      case "gitlab":
        return new GitLabInfo(environment);
      case "azure":
        return new AzurePipelinesInfo(environment);
      case "travis":
        return new TravisInfo(environment);
      default:
        return new JenkinsInfo(environment);
    }
  }

  private static String appveyorSha(String value) {
    if ("head".equals(value)) {
      return SHA;
    }
    if ("upper".equals(value)) {
      return SHA.toUpperCase(Locale.ROOT);
    }
    if ("build".equals(value)) {
      return APPVEYOR_BUILD_SHA;
    }
    return value;
  }
}
