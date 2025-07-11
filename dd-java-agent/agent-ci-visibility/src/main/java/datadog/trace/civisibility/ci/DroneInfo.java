package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;
import static datadog.trace.api.git.GitUtils.normalizeBranch;
import static datadog.trace.api.git.GitUtils.normalizeTag;
import static datadog.trace.civisibility.utils.FileUtils.expandTilde;

import datadog.trace.api.civisibility.telemetry.tag.Provider;
import datadog.trace.api.git.CommitInfo;
import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.PersonInfo;
import datadog.trace.civisibility.ci.env.CiEnvironment;
import javax.annotation.Nonnull;

public class DroneInfo implements CIProviderInfo {

  public static final String DRONE = "DRONE";
  public static final String DRONE_PROVIDER_NAME = "drone";
  public static final String DRONE_BUILD_NUMBER = "DRONE_BUILD_NUMBER";
  public static final String DRONE_BUILD_LINK = "DRONE_BUILD_LINK";
  public static final String DRONE_STEP_NAME = "DRONE_STEP_NAME";
  public static final String DRONE_STAGE_NAME = "DRONE_STAGE_NAME";
  public static final String DRONE_WORKSPACE = "DRONE_WORKSPACE";
  public static final String DRONE_GIT_HTTP_URL = "DRONE_GIT_HTTP_URL";
  public static final String DRONE_COMMIT_SHA = "DRONE_COMMIT_SHA";
  public static final String DRONE_BRANCH = "DRONE_BRANCH";
  public static final String DRONE_TAG = "DRONE_TAG";
  public static final String DRONE_COMMIT_AUTHOR_NAME = "DRONE_COMMIT_AUTHOR_NAME";
  public static final String DRONE_COMMIT_AUTHOR_EMAIL = "DRONE_COMMIT_AUTHOR_EMAIL";
  public static final String DRONE_COMMIT_MESSAGE = "DRONE_COMMIT_MESSAGE";
  public static final String DRONE_PULL_REQUEST_NUMBER = "DRONE_PULL_REQUEST";
  public static final String DRONE_PULL_REQUEST_TARGET_BRANCH = "DRONE_TARGET_BRANCH";

  private final CiEnvironment environment;

  DroneInfo(CiEnvironment environment) {
    this.environment = environment;
  }

  @Override
  public GitInfo buildCIGitInfo() {
    return new GitInfo(
        filterSensitiveInfo(environment.get(DRONE_GIT_HTTP_URL)),
        normalizeBranch(environment.get(DRONE_BRANCH)),
        normalizeTag(environment.get(DRONE_TAG)),
        new CommitInfo(
            environment.get(DRONE_COMMIT_SHA),
            buildGitAuthor(),
            PersonInfo.NOOP,
            environment.get(DRONE_COMMIT_MESSAGE)));
  }

  @Override
  public CIInfo buildCIInfo() {
    return CIInfo.builder(environment)
        .ciProviderName(DRONE_PROVIDER_NAME)
        .ciPipelineNumber(environment.get(DRONE_BUILD_NUMBER))
        .ciPipelineUrl(environment.get(DRONE_BUILD_LINK))
        .ciJobName(environment.get(DRONE_STEP_NAME))
        .ciStageName(environment.get(DRONE_STAGE_NAME))
        .ciWorkspace(expandTilde(environment.get(DRONE_WORKSPACE)))
        .build();
  }

  @Nonnull
  @Override
  public PullRequestInfo buildPullRequestInfo() {
    return new PullRequestInfo(
        normalizeBranch(environment.get(DRONE_PULL_REQUEST_TARGET_BRANCH)),
        null,
        CommitInfo.NOOP,
        environment.get(DRONE_PULL_REQUEST_NUMBER));
  }

  private PersonInfo buildGitAuthor() {
    return new PersonInfo(
        environment.get(DRONE_COMMIT_AUTHOR_NAME), environment.get(DRONE_COMMIT_AUTHOR_EMAIL));
  }

  @Override
  public Provider getProvider() {
    return Provider.DRONE;
  }
}
