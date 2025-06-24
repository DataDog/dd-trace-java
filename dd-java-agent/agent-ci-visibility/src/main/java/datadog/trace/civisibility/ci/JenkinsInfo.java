package datadog.trace.civisibility.ci;

import static datadog.json.JsonMapper.toJson;
import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.isTagReference;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;

@SuppressForbidden
class JenkinsInfo implements CIProviderInfo {

  // https://wiki.jenkins.io/display/JENKINS/Building+a+software+project
  public static final String JENKINS = "JENKINS_URL";
  public static final String JENKINS_PROVIDER_NAME = "jenkins";
  public static final String JENKINS_PIPELINE_ID = "BUILD_TAG";
  public static final String JENKINS_PIPELINE_NUMBER = "BUILD_NUMBER";
  public static final String JENKINS_PIPELINE_URL = "BUILD_URL";
  public static final String JENKINS_PIPELINE_NAME = "JOB_NAME";
  public static final String JENKINS_JOB_URL = "JOB_URL";
  public static final String JENKINS_WORKSPACE_PATH = "WORKSPACE";
  public static final String JENKINS_GIT_REPOSITORY_URL = "GIT_URL";
  public static final String JENKINS_GIT_REPOSITORY_URL_ALT = "GIT_URL_1";
  public static final String JENKINS_GIT_COMMIT = "GIT_COMMIT";
  public static final String JENKINS_GIT_BRANCH = "GIT_BRANCH";
  public static final String JENKINS_DD_CUSTOM_TRACE_ID = "DD_CUSTOM_TRACE_ID";
  public static final String JENKINS_NODE_NAME = "NODE_NAME";
  public static final String JENKINS_NODE_LABELS = "NODE_LABELS";
  public static final String JENKINS_PR_NUMBER = "CHANGE_ID";
  public static final String JENKINS_PR_BASE_BRANCH = "CHANGE_TARGET";

  private final CiEnvironment environment;

  JenkinsInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(buildGitRepositoryUrl()),
        buildGitBranch(),
        buildGitTag(),
        new CommitInfo(environment.get(JENKINS_GIT_COMMIT)));
  }

  @Override
  public CIInfo buildCIInfo() {
    final String gitBranch = buildGitBranch();

    return CIInfo.builder(environment)
        .ciProviderName(JENKINS_PROVIDER_NAME)
        .ciPipelineId(environment.get(JENKINS_PIPELINE_ID))
        .ciPipelineName(buildCiPipelineName(gitBranch))
        .ciPipelineNumber(environment.get(JENKINS_PIPELINE_NUMBER))
        .ciPipelineUrl(environment.get(JENKINS_PIPELINE_URL))
        .ciWorkspace(expandTilde(environment.get(JENKINS_WORKSPACE_PATH)))
        .ciNodeName(environment.get(JENKINS_NODE_NAME))
        .ciNodeLabels(buildCiNodeLabels())
        .ciEnvVars(JENKINS_DD_CUSTOM_TRACE_ID)
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(JENKINS_PR_BASE_BRANCH)),
        null,
        null,
        environment.get(JENKINS_PR_NUMBER));
  }

  private String buildCiNodeLabels() {
    String labels = environment.get(JENKINS_NODE_LABELS);
    if (labels == null || labels.isEmpty()) {
      return labels;
    }
    return toJson(labels.split(" "));
  }

  private String buildGitRepositoryUrl() {
    return environment.get(JENKINS_GIT_REPOSITORY_URL) != null
        ? environment.get(JENKINS_GIT_REPOSITORY_URL)
        : environment.get(JENKINS_GIT_REPOSITORY_URL_ALT);
  }

  private String buildGitBranch() {
    final String gitBranchOrTag = environment.get(JENKINS_GIT_BRANCH);
    if (!isTagReference(gitBranchOrTag)) {
      return normalizeBranch(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildGitTag() {
    final String gitBranchOrTag = environment.get(JENKINS_GIT_BRANCH);
    if (isTagReference(gitBranchOrTag)) {
      return normalizeTag(gitBranchOrTag);
    } else {
      return null;
    }
  }

  private String buildCiPipelineName(final String branch) {
    final String jobName = environment.get(JENKINS_PIPELINE_NAME);
    return filterJenkinsJobName(jobName, branch);
  }

  private String filterJenkinsJobName(final String jobName, final String gitBranch) {
    if (jobName == null) {
      return null;
    }

    // First, the git branch is removed from the raw jobName
    final String jobNameNoBranch;
    if (gitBranch != null) {
      jobNameNoBranch = jobName.trim().replace("/" + gitBranch, "");
    } else {
      jobNameNoBranch = jobName;
    }

    // Once the branch has been removed, we try to extract
    // the configurations from the job name.
    // The configurations have the form like "key1=value1,key2=value2"
    final Map<String, String> configurations = new HashMap<>();
    final String[] jobNameParts = jobNameNoBranch.split("/");
    if (jobNameParts.length > 1 && jobNameParts[1].contains("=")) {
      final String configsStr = jobNameParts[1].toLowerCase(Locale.ROOT).trim();
      final String[] configsKeyValue = configsStr.split(",");
      for (final String configKeyValue : configsKeyValue) {
        final String[] keyValue = configKeyValue.trim().split("=");
        configurations.put(keyValue[0], keyValue[1]);
      }
    }

    if (configurations.isEmpty()) {
      // If there is no configurations,
      // the jobName is the original one without branch.
      return jobNameNoBranch;
    } else {
      // If there are configurations,
      // the jobName is the first part of the splited raw jobName.
      return jobNameParts[0];
    }
  }

  @Override
  public Provider getProvider() {
    return Provider.JENKINS;
  }
}
