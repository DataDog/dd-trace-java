package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.AZURE_APP_SERVICES;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(WithConfigExtension.class)
class AzureAppServicesTagsTest {

  private static final String SUBSCRIPTION_ID = "8c500027-5f00-400e-8f00-60000000000f";

  @Test
  @WithConfig(key = "WEBSITE_SITE_NAME", value = "someValue", env = true, addPrefix = false)
  @WithConfig(key = "WEBSITE_INSTANCE_ID", value = "someOtherValue", env = true, addPrefix = false)
  void azureTagsAreOnlyAddedWhenAzureAppServicesFlagSet() {
    Config config = Config.get();

    assertEquals(emptyList(), aasKeys(config.getLocalRootSpanTags().keySet()));
    assertEquals(emptyList(), aasKeys(config.getMergedProfilingTags().keySet()));
  }

  @Test
  @WithConfig(key = AZURE_APP_SERVICES, value = "true")
  @WithConfig(
      key = "WEBSITE_OWNER_NAME",
      value = SUBSCRIPTION_ID + "+apm-dotnet-EastUSwebspace",
      env = true,
      addPrefix = false)
  void subscriptionIdParsed() {
    Config config = Config.get();

    assertEquals(SUBSCRIPTION_ID, config.getLocalRootSpanTags().getString("aas.subscription.id"));
    assertEquals(SUBSCRIPTION_ID, config.getMergedProfilingTags().get("aas.subscription.id"));
  }

  @Test
  @WithConfig(key = AZURE_APP_SERVICES, value = "true")
  @WithConfig(
      key = "WEBSITE_OWNER_NAME",
      value = SUBSCRIPTION_ID + "+apm-dotnet-EastUSwebspace",
      env = true,
      addPrefix = false)
  @WithConfig(key = "WEBSITE_RESOURCE_GROUP", value = "group", env = true, addPrefix = false)
  @WithConfig(key = "WEBSITE_SITE_NAME", value = "site", env = true, addPrefix = false)
  void resourceIdGenerated() {
    Config config = Config.get();

    String expectedResourceId =
        "/subscriptions/"
            + SUBSCRIPTION_ID
            + "/resourcegroups/group/providers/microsoft.web/sites/site";
    assertEquals(expectedResourceId, config.getLocalRootSpanTags().getString("aas.resource.id"));
    assertEquals(expectedResourceId, config.getMergedProfilingTags().get("aas.resource.id"));
  }

  @Test
  @WithConfig(key = AZURE_APP_SERVICES, value = "true")
  void someTagsAreSetToUnknownWhenNotDefined() {
    Config config = Config.get();

    TagMap localRootSpanTags = config.getLocalRootSpanTags();
    assertEquals("unknown", localRootSpanTags.getString("aas.environment.instance_id"));
    assertEquals("unknown", localRootSpanTags.getString("aas.environment.instance_name"));
    assertEquals("unknown", localRootSpanTags.getString("aas.environment.os"));
    assertEquals("unknown", localRootSpanTags.getString("aas.environment.extension_version"));
    Map<String, String> mergedProfilingTags = config.getMergedProfilingTags();
    assertEquals("unknown", mergedProfilingTags.get("aas.environment.instance_id"));
    assertEquals("unknown", mergedProfilingTags.get("aas.environment.instance_name"));
    assertEquals("unknown", mergedProfilingTags.get("aas.environment.os"));
    assertEquals("unknown", mergedProfilingTags.get("aas.environment.extension_version"));
  }

  @Test
  @WithConfig(key = AZURE_APP_SERVICES, value = "true")
  @WithConfig(key = "WEBSITE_SITE_NAME", value = "someSite", env = true, addPrefix = false)
  @WithConfig(key = "WEBSITE_INSTANCE_ID", value = "someInstance", env = true, addPrefix = false)
  @WithConfig(key = "COMPUTERNAME", value = "someComputer", env = true, addPrefix = false)
  @WithConfig(key = "WEBSITE_OS", value = "someOs", env = true, addPrefix = false)
  @WithConfig(key = "DD_AAS_JAVA_EXTENSION_VERSION", value = "99", env = true, addPrefix = false)
  void tagsAreSet() {
    Config config = Config.get();

    TagMap localRootSpanTags = config.getLocalRootSpanTags();
    assertEquals("someInstance", localRootSpanTags.getString("aas.environment.instance_id"));
    assertEquals("someComputer", localRootSpanTags.getString("aas.environment.instance_name"));
    assertEquals("someOs", localRootSpanTags.getString("aas.environment.os"));
    assertEquals("99", localRootSpanTags.getString("aas.environment.extension_version"));
    Map<String, String> mergedProfilingTags = config.getMergedProfilingTags();
    assertEquals("someInstance", mergedProfilingTags.get("aas.environment.instance_id"));
    assertEquals("someComputer", mergedProfilingTags.get("aas.environment.instance_name"));
    assertEquals("someOs", mergedProfilingTags.get("aas.environment.os"));
    assertEquals("99", mergedProfilingTags.get("aas.environment.extension_version"));
  }

  private static List<String> aasKeys(Collection<String> keys) {
    return keys.stream().filter(key -> key.startsWith("aas")).collect(toList());
  }
}
