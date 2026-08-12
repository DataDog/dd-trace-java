import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.featureflag.FeatureFlaggingGateway
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.NoOpProvider
import dev.openfeature.sdk.OpenFeatureAPI

import static java.util.Collections.emptyMap

class OpenFeatureProviderInjectionTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("trace.enabled", "false")
    injectSysConfig("trace.openfeature.enabled", "true")
    FeatureFlaggingGateway.setProviderInjectionEnabled(true)
  }

  def cleanup() {
    FeatureFlaggingGateway.setProviderInjectionEnabled(false)
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null)
    OpenFeatureAPI.getInstance().shutdown()
  }

  def "injects once only after explicit activation"() {
    given: "the instrumentation loaded for Feature Flags but provider installation is disabled"
    FeatureFlaggingGateway.setProviderInjectionEnabled(false)

    when: "OpenFeature loads without explicit Feature Flags activation"
    def api = OpenFeatureAPI.getInstance()

    then:
    TRANSFORMED_CLASSES_NAMES.contains("dev.openfeature.sdk.OpenFeatureAPI")
    api.provider.class == NoOpProvider

    when: "the Java agent enables provider injection"
    FeatureFlaggingGateway.dispatch(new ServerConfiguration(null, null, null, emptyMap()))
    FeatureFlaggingGateway.setProviderInjectionEnabled(true)
    api = OpenFeatureAPI.getInstance()

    then:
    api.provider.metadata.name == "datadog-openfeature-provider"

    when: "application code selects another provider"
    def customerProvider = new CustomerProvider()
    api.setProvider(customerProvider)
    OpenFeatureAPI.getInstance()

    then:
    api.provider.is(customerProvider)
  }

  private static final class CustomerProvider extends NoOpProvider {
    @Override
    Metadata getMetadata() {
      return { "customer-provider" }
    }
  }
}
