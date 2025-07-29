package datadog.trace.instrumentation.openai;

import com.openai.azure.credential.AzureApiKeyCredential;
import com.openai.core.ClientOptions;
import com.openai.credential.BearerTokenCredential;

public class OpenAIClientInfo {
  private String baseURL;
  private String organizationID;
  private String projectID;
  private String apiKey;

  /**
   * Creates an OpenAIClientInfo instance populated with relevant information from ClientOptions for
   * use in span tags during tests and instrumentation
   */
  public static OpenAIClientInfo fromClientOptions(ClientOptions options) {
    if (options == null) {
      return null;
    }

    OpenAIClientInfo info = new OpenAIClientInfo();

    try {
      // Extract base URL
      info.setBaseURL(options.baseUrl());

      // Extract organization ID if available
      if (options.organization().isPresent()) {
        info.setOrganizationID(options.organization().get());
      }

      // Extract project ID if available
      if (options.project().isPresent()) {
        info.setProjectID(options.project().get());
      }

      if (options.credential() instanceof BearerTokenCredential) {
        info.setApiKey(((BearerTokenCredential) options.credential()).token());
      } else if (options.credential() instanceof AzureApiKeyCredential) {
        info.setApiKey(((AzureApiKeyCredential) options.credential()).apiKey());
      } else info.setApiKey(null);

    } catch (Exception e) {
      // TODO: returns empty info for now
      return info;
    }

    return info;
  }

  private OpenAIClientInfo() {
    // Used by fromClientOptions method
  }

  public String getBaseURL() {
    return baseURL;
  }

  public String getOrganizationID() {
    return organizationID;
  }

  public String getProjectID() {
    return projectID;
  }

  public String getApiKey() {
    return apiKey;
  }

  // Private setter methods (write properties)
  private void setBaseURL(String baseURL) {
    this.baseURL = baseURL;
  }

  private void setOrganizationID(String organizationID) {
    this.organizationID = organizationID;
  }

  private void setProjectID(String projectID) {
    this.projectID = projectID;
  }

  private void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }
}
