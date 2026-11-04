package datadog.smoketest.springboot.openfeature;

import com.datadog.openfeature.Provider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenFeatureConfiguration {

  /**
   * @param lazyProvider whether to wait for {@code POST /openfeature/provider} to register the
   *     provider, instead of registering it at startup.
   * @return the OpenFeature client.
   */
  @Bean
  public Client openFeatureClient(
      @Value("${openfeature.lazy-provider:false}") final boolean lazyProvider) {
    OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    if (!lazyProvider) {
      api.setProviderAndWait(new Provider());
    }
    return api.getClient();
  }
}
