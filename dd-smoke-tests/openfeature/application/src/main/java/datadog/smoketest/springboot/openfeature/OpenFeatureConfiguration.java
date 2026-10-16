package datadog.smoketest.springboot.openfeature;

import datadog.trace.api.openfeature.Provider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenFeatureConfiguration {

  @Bean
  public Client openFeatureClient() {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(
        new Provider(
            new Provider.Options()
                .enableExperimentationOptOutParameter(
                    Boolean.getBoolean("smoketest.experimentationOptOutParameterEnabled"))));
    return api.getClient();
  }
}
