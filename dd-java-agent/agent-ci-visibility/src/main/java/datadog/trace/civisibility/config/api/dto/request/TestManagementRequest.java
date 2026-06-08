package datadog.trace.civisibility.config.api.dto.request;

import com.squareup.moshi.Json;

public final class TestManagementRequest {
  @Json(name = "repository_url")
  public final String repositoryUrl;

  @Json(name = "commit_message")
  public final String commitMessage;

  public final String module;
  public final String sha;
  public final String branch;

  public TestManagementRequest(
      String repositoryUrl, String commitMessage, String module, String sha, String branch) {
    this.repositoryUrl = repositoryUrl;
    this.commitMessage = commitMessage;
    this.module = module;
    this.sha = sha;
    this.branch = branch;
  }
}
