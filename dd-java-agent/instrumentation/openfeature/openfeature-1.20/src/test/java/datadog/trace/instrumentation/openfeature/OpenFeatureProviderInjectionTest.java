package datadog.trace.instrumentation.openfeature;

import static datadog.trace.api.featureflag.config.FeatureFlaggingConfig.FEATURE_FLAGS_CONFIGURATION_SOURCE;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.test.junit.utils.config.WithConfig;
import dev.openfeature.sdk.Metadata;
import dev.openfeature.sdk.NoOpProvider;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@WithConfig(key = "trace.enabled", value = "false")
@WithConfig(key = FEATURE_FLAGS_CONFIGURATION_SOURCE, value = "agentless")
class OpenFeatureProviderInjectionTest extends AbstractInstrumentationTest {

  @BeforeEach
  void publishConfiguration() {
    FeatureFlaggingGateway.dispatch(new ServerConfiguration(null, null, false, null, emptyMap()));
  }

  @AfterEach
  void resetOpenFeature() {
    OpenFeatureAPI.getInstance().shutdown();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
  }

  @Test
  void injectsWithTracingDisabledAndPreservesCustomerReplacement() {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();

    assertEquals("datadog-openfeature-provider", api.getProvider().getMetadata().getName());

    final CustomerProvider customerProvider = new CustomerProvider();
    api.setProvider(customerProvider);
    OpenFeatureAPI.getInstance();

    assertSame(customerProvider, api.getProvider());
  }

  private static final class CustomerProvider extends NoOpProvider {
    @Override
    public Metadata getMetadata() {
      return () -> "customer-provider";
    }
  }
}
