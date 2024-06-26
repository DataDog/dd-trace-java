package datadog.trace.civisibility.ci;

import static datadog.trace.api.git.GitUtils.filterSensitiveInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class CIInfo {
  public static final CIInfo NOOP = new CIInfo();

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private String ciProviderName;
    private String ciPipelineId;
    private String ciPipelineName;
    private String ciStageName;
    private String ciJobName;
    private String ciPipelineNumber;
    private String ciPipelineUrl;
    private String ciJobUrl;
    private String ciWorkspace;
    private String ciNodeName;
    private String ciNodeLabels;
    private Map<String, String> ciEnvVars;

    public Builder ciProviderName(String ciProviderName) {
      this.ciProviderName = ciProviderName;
      return this;
    }

    public Builder ciPipelineId(String ciPipelineId) {
      this.ciPipelineId = ciPipelineId;
      return this;
    }

    public Builder ciPipelineName(String ciPipelineName) {
      this.ciPipelineName = ciPipelineName;
      return this;
    }

    public Builder ciStageName(String ciStageName) {
      this.ciStageName = ciStageName;
      return this;
    }

    public Builder ciJobName(String ciJobName) {
      this.ciJobName = ciJobName;
      return this;
    }

    public Builder ciPipelineNumber(String ciPipelineNumber) {
      this.ciPipelineNumber = ciPipelineNumber;
      return this;
    }

    public Builder ciPipelineUrl(String ciPipelineUrl) {
      this.ciPipelineUrl = ciPipelineUrl;
      return this;
    }

    public Builder ciJobUrl(String ciJobUrl) {
      this.ciJobUrl = ciJobUrl;
      return this;
    }

    public Builder ciWorkspace(String ciWorkspace) {
      this.ciWorkspace = ciWorkspace;
      return this;
    }

    public Builder ciNodeName(String ciNodeName) {
      this.ciNodeName = ciNodeName;
      return this;
    }

    public Builder ciNodeLabels(String ciNodeLabels) {
      this.ciNodeLabels = ciNodeLabels;
      return this;
    }

    public Builder ciEnvVars(String... ciEnvVarKeysArray) {
      if (ciEnvVarKeysArray == null || ciEnvVarKeysArray.length == 0) {
        return this;
      }

      ciEnvVars = new HashMap<>();
      for (String ciEnvVarKey : ciEnvVarKeysArray) {
        final String envVarVal = filterSensitiveInfo(System.getenv(ciEnvVarKey));
        if (envVarVal != null && !envVarVal.isEmpty()) {
          ciEnvVars.put(ciEnvVarKey, envVarVal);
        }
      }
      return this;
    }

    public CIInfo build() {
      return new CIInfo(
          ciProviderName,
          ciPipelineId,
          ciPipelineName,
          ciStageName,
          ciJobName,
          ciPipelineNumber,
          ciPipelineUrl,
          ciJobUrl,
          ciWorkspace,
          ciNodeName,
          ciNodeLabels,
          ciEnvVars);
    }
  }

  private final String ciProviderName;
  private final String ciPipelineId;
  private final String ciPipelineName;
  private final String ciStageName;
  private final String ciJobName;
  private final String ciPipelineNumber;
  private final String ciPipelineUrl;
  private final String ciJobUrl;
  private final String ciWorkspace;
  private final String ciNodeName;
  private final String ciNodeLabels;
  private final Map<String, String> ciEnvVars;

  public CIInfo() {
    this(null, null, null, null, null, null, null, null, null, null, null, null);
  }

  public CIInfo(
      String ciProviderName,
      String ciPipelineId,
      String ciPipelineName,
      String ciStageName,
      String ciJobName,
      String ciPipelineNumber,
      String ciPipelineUrl,
      String ciJobUrl,
      String ciWorkspace,
      String ciNodeName,
      String ciNodeLabels,
      Map<String, String> ciEnvVars) {
    this.ciProviderName = ciProviderName;
    this.ciPipelineId = ciPipelineId;
    this.ciPipelineName = ciPipelineName;
    this.ciStageName = ciStageName;
    this.ciJobName = ciJobName;
    this.ciPipelineNumber = ciPipelineNumber;
    this.ciPipelineUrl = ciPipelineUrl;
    this.ciJobUrl = ciJobUrl;
    this.ciWorkspace = ciWorkspace;
    this.ciNodeName = ciNodeName;
    this.ciNodeLabels = ciNodeLabels;
    this.ciEnvVars = ciEnvVars;
  }

  public String getCiProviderName() {
    return ciProviderName;
  }

  public String getCiPipelineId() {
    return ciPipelineId;
  }

  public String getCiPipelineName() {
    return ciPipelineName;
  }

  public String getCiStageName() {
    return ciStageName;
  }

  public String getCiJobName() {
    return ciJobName;
  }

  public String getCiPipelineNumber() {
    return ciPipelineNumber;
  }

  public String getCiPipelineUrl() {
    return ciPipelineUrl;
  }

  public String getCiJobUrl() {
    return ciJobUrl;
  }

  public String getCiWorkspace() {
    return ciWorkspace;
  }

  public String getCiNodeName() {
    return ciNodeName;
  }

  public String getCiNodeLabels() {
    return ciNodeLabels;
  }

  public Map<String, String> getCiEnvVars() {
    return ciEnvVars;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CIInfo ciInfo = (CIInfo) o;
    return Objects.equals(ciProviderName, ciInfo.ciProviderName)
        && Objects.equals(ciPipelineId, ciInfo.ciPipelineId)
        && Objects.equals(ciPipelineName, ciInfo.ciPipelineName)
        && Objects.equals(ciStageName, ciInfo.ciStageName)
        && Objects.equals(ciJobName, ciInfo.ciJobName)
        && Objects.equals(ciPipelineNumber, ciInfo.ciPipelineNumber)
        && Objects.equals(ciPipelineUrl, ciInfo.ciPipelineUrl)
        && Objects.equals(ciJobUrl, ciInfo.ciJobUrl)
        && Objects.equals(ciWorkspace, ciInfo.ciWorkspace)
        && Objects.equals(ciNodeName, ciInfo.ciNodeName)
        && Objects.equals(ciNodeLabels, ciInfo.ciNodeLabels)
        && Objects.equals(ciEnvVars, ciInfo.ciEnvVars);
  }

  @Override
  public int hashCode() {
    int hash = 1;
    hash = 31 * hash + (ciProviderName == null ? 0 : ciProviderName.hashCode());
    hash = 31 * hash + (ciPipelineId == null ? 0 : ciPipelineId.hashCode());
    hash = 31 * hash + (ciPipelineName == null ? 0 : ciPipelineName.hashCode());
    hash = 31 * hash + (ciStageName == null ? 0 : ciStageName.hashCode());
    hash = 31 * hash + (ciJobName == null ? 0 : ciJobName.hashCode());
    hash = 31 * hash + (ciPipelineNumber == null ? 0 : ciPipelineNumber.hashCode());
    hash = 31 * hash + (ciPipelineUrl == null ? 0 : ciPipelineUrl.hashCode());
    hash = 31 * hash + (ciJobUrl == null ? 0 : ciJobUrl.hashCode());
    hash = 31 * hash + (ciWorkspace == null ? 0 : ciWorkspace.hashCode());
    hash = 31 * hash + (ciNodeName == null ? 0 : ciNodeName.hashCode());
    hash = 31 * hash + (ciNodeLabels == null ? 0 : ciNodeLabels.hashCode());
    hash = 31 * hash + (ciEnvVars == null ? 0 : ciEnvVars.hashCode());
    return hash;
  }

  @Override
  public String toString() {
    return "CIInfo{"
        + "ciProviderName='"
        + ciProviderName
        + '\''
        + ", ciPipelineId='"
        + ciPipelineId
        + '\''
        + ", ciPipelineName='"
        + ciPipelineName
        + '\''
        + ", ciStageName='"
        + ciStageName
        + '\''
        + ", ciJobName='"
        + ciJobName
        + '\''
        + ", ciPipelineNumber='"
        + ciPipelineNumber
        + '\''
        + ", ciPipelineUrl='"
        + ciPipelineUrl
        + '\''
        + ", ciJobUrl='"
        + ciJobUrl
        + '\''
        + ", ciWorkspace='"
        + ciWorkspace
        + '\''
        + ", ciNodeName='"
        + ciNodeName
        + '\''
        + ", ciNodeLabels='"
        + ciNodeLabels
        + '\''
        + ", ciEnvVars='"
        + ciEnvVars
        + '\''
        + '}';
  }
}
