package datadog.trace.api

import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.DDSpecification

class AzureAppServicesTagsTest extends DDSpecification {

  def "azure tags are only added when azure app services flag set"() {
    setup:
    injectEnvConfig("WEBSITE_SITE_NAME", "someValue", false)
    injectEnvConfig("WEBSITE_INSTANCE_ID", "someOtherValue", false)

    when:
    def config = new Config()

    then:
    !config.localRootSpanTags.keySet().any { it.startsWith("aas")}
  }

  def "subscription id parsed"() {
    setup:
    injectSysConfig(GeneralConfig.AZURE_APP_SERVICES, "true")
    injectEnvConfig("WEBSITE_OWNER_NAME", "8c500027-5f00-400e-8f00-60000000000f+apm-dotnet-EastUSwebspace", false)

    when:
    def config = new Config()

    then:
    config.localRootSpanTags["aas.subscription.id"] == "8c500027-5f00-400e-8f00-60000000000f"
  }

  def "resource id generated"() {
    setup:
    injectSysConfig(GeneralConfig.AZURE_APP_SERVICES, "true")
    injectEnvConfig("WEBSITE_OWNER_NAME", "8c500027-5f00-400e-8f00-60000000000f+apm-dotnet-EastUSwebspace", false)
    injectEnvConfig("WEBSITE_RESOURCE_GROUP", "group", false)
    injectEnvConfig("WEBSITE_SITE_NAME", "site", false)

    when:
    def config = new Config()

    then:
    config.localRootSpanTags["aas.resource.id"] == "/subscriptions/8c500027-5f00-400e-8f00-60000000000f/resourcegroups/group/providers/microsoft.web/sites/site"
  }

  def "some tags are set to unknown when not defined"() {
    setup:
    injectSysConfig(GeneralConfig.AZURE_APP_SERVICES, "true")

    when:
    def config = new Config()

    then:
    config.localRootSpanTags["aas.environment.instance_id"] == "unknown"
    config.localRootSpanTags["aas.environment.instance_name"] == "unknown"
    config.localRootSpanTags["aas.environment.os"] == "unknown"
    config.localRootSpanTags["aas.environment.extension_version"] == "unknown"
  }

  def "tags are set"() {
    setup:
    injectSysConfig(GeneralConfig.AZURE_APP_SERVICES, "true")
    injectEnvConfig("WEBSITE_SITE_NAME", "someSite", false)
    injectEnvConfig("WEBSITE_INSTANCE_ID", "someInstance", false)
    injectEnvConfig("COMPUTERNAME", "someComputer", false)
    injectEnvConfig("WEBSITE_OS", "someOs", false)
    injectEnvConfig("DD_AAS_JAVA_EXTENSION_VERSION", "99", false)

    when:
    def config = new Config()

    then:
    config.localRootSpanTags["aas.environment.instance_id"] == "someInstance"
    config.localRootSpanTags["aas.environment.instance_name"] == "someComputer"
    config.localRootSpanTags["aas.environment.os"] == "someOs"
    config.localRootSpanTags["aas.environment.extension_version"] == "99"
  }
}
