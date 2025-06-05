package datadog.trace.civisibility.config;

import com.squareup.moshi.Json;
import datadog.trace.api.civisibility.config.Configurations;
import datadog.trace.util.Strings;
import java.util.HashMap;
import java.util.Map;

public class TracerEnvironment {

  private final String service;
  private final String env;

  @Json(name = "repository_url")
  private final String repositoryUrl;

  private final String branch;
  private final String sha;

  @Json(name = "commit_message")
  private final String commitMessage;

  @Json(name = "test_level")
  private final String testLevel = "test";

  private final Configurations configurations;

  private TracerEnvironment(
      String service,
      String env,
      String repositoryUrl,
      String branch,
      String sha,
      String commitMessage,
      Configurations configurations) {
    this.service = service;
    this.env = env;
    this.repositoryUrl = repositoryUrl;
    this.branch = branch;
    this.sha = sha;
    this.commitMessage = commitMessage;
    this.configurations = configurations;
  }

  public String getSha() {
    return sha;
  }

  public String getCommitMessage() {
    return commitMessage;
  }

  public String getService() {
    return service;
  }

  public String getEnv() {
    return env;
  }

  public String getRepositoryUrl() {
    return repositoryUrl;
  }

  public String getBranch() {
    return branch;
  }

  public String getTestLevel() {
    return testLevel;
  }

  public Configurations getConfigurations() {
    return configurations;
  }

  @Override
  public String toString() {
    return "TracerEnvironment{"
        + "service='"
        + service
        + '\''
        + ", env='"
        + env
        + '\''
        + ", repositoryUrl='"
        + repositoryUrl
        + '\''
        + ", branch='"
        + branch
        + '\''
        + ", sha='"
        + sha
        + '\''
        + ", commitMessage='"
        + commitMessage
        + '\''
        + ", testLevel='"
        + testLevel
        + '\''
        + ", configurations="
        + configurations
        + '}';
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String service;
    private String env;
    private String repositoryUrl;
    private String branch;
    private String tag; // will act as fallback if no branch is provided
    private String sha;
    private String commitMessage;
    private String osPlatform;
    private String osArchitecture;
    private String osVersion;
    private String runtimeName;
    private String runtimeVersion;
    private String runtimeVendor;
    private String runtimeArchitecture;
    private String testBundle;
    private final Map<String, String> customTags = new HashMap<>();

    public Builder service(String service) {
      this.service = service;
      return this;
    }

    public Builder env(String env) {
      this.env = env;
      return this;
    }

    public Builder repositoryUrl(String repositoryUrl) {
      this.repositoryUrl = repositoryUrl;
      return this;
    }

    public Builder branch(String branch) {
      this.branch = branch;
      return this;
    }

    public Builder tag(String tag) {
      this.tag = tag;
      return this;
    }

    public Builder sha(String sha) {
      this.sha = sha;
      return this;
    }

    public Builder commitMessage(String commitMessage) {
      this.commitMessage = commitMessage;
      return this;
    }

    public Builder osPlatform(String osPlatform) {
      this.osPlatform = osPlatform;
      return this;
    }

    public Builder osArchitecture(String osArchitecture) {
      this.osArchitecture = osArchitecture;
      return this;
    }

    public Builder osVersion(String osVersion) {
      this.osVersion = osVersion;
      return this;
    }

    public Builder runtimeName(String runtimeName) {
      this.runtimeName = runtimeName;
      return this;
    }

    public Builder runtimeVersion(String runtimeVersion) {
      this.runtimeVersion = runtimeVersion;
      return this;
    }

    public Builder runtimeVendor(String runtimeVendor) {
      this.runtimeVendor = runtimeVendor;
      return this;
    }

    public Builder runtimeArchitecture(String runtimeArchitecture) {
      this.runtimeArchitecture = runtimeArchitecture;
      return this;
    }

    public Builder testBundle(String testBundle) {
      this.testBundle = testBundle;
      return this;
    }

    public Builder customTag(String key, String value) {
      this.customTags.put(key, value);
      return this;
    }

    public TracerEnvironment build() {
      return new TracerEnvironment(
          service,
          env,
          repositoryUrl,
          Strings.isNotBlank(branch) ? branch : tag,
          sha,
          commitMessage,
          new Configurations(
              osPlatform,
              osArchitecture,
              osVersion,
              runtimeName,
              runtimeVersion,
              runtimeVendor,
              runtimeArchitecture,
              testBundle,
              customTags));
    }
  }
}
