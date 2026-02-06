package datadog.trace.civisibility.config;

import com.squareup.moshi.Json;
import datadog.trace.api.civisibility.config.Configurations;
import datadog.trace.util.Strings;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

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

  @Json(name = "page_info")
  @Nullable
  private final PageInfoRequest pageInfo;

  private TracerEnvironment(
      String service,
      String env,
      String repositoryUrl,
      String branch,
      String sha,
      String commitMessage,
      Configurations configurations,
      @Nullable PageInfoRequest pageInfo) {
    this.service = service;
    this.env = env;
    this.repositoryUrl = repositoryUrl;
    this.branch = branch;
    this.sha = sha;
    this.commitMessage = commitMessage;
    this.configurations = configurations;
    this.pageInfo = pageInfo;
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

  @Nullable
  public PageInfoRequest getPageInfo() {
    return pageInfo;
  }

  /**
   * Creates a copy of this TracerEnvironment with the specified pagination state. Used for
   * paginated API requests where page_info needs to be included in the request attributes.
   *
   * @param pageState the cursor token for the next page (null for first page, which sends empty
   *     page_info object to let backend use default page size)
   * @return a new TracerEnvironment instance with page_info set
   */
  public TracerEnvironment withPageInfo(@Nullable String pageState) {
    return new TracerEnvironment(
        this.service,
        this.env,
        this.repositoryUrl,
        this.branch,
        this.sha,
        this.commitMessage,
        this.configurations,
        new PageInfoRequest(pageState));
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
              customTags),
          null);
    }
  }

  /**
   * Request pagination information. When serialized as an empty object (no page_state), the backend
   * uses its default page size. The page_state is only included for subsequent page requests.
   */
  public static final class PageInfoRequest {
    @Json(name = "page_state")
    @Nullable
    private final String pageState;

    public PageInfoRequest(@Nullable String pageState) {
      this.pageState = pageState;
    }

    @Nullable
    public String getPageState() {
      return pageState;
    }
  }
}
