package datadog.trace.api.openfeature;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** Checks the deprecated provider alias stays source and binary compatible. */
@SuppressWarnings("deprecation")
class ProviderTest {
  @Test
  void aliasIsTheDatadogProvider() {
    final Provider provider = new Provider();

    assertInstanceOf(com.datadog.openfeature.Provider.class, provider);
    assertEquals("datadog-openfeature-provider", provider.getMetadata().getName());
  }

  @Test
  void aliasOptionsKeepTheirType() {
    final Provider.Options options = new Provider.Options();

    final Provider.Options configured = options.initTimeout(5, SECONDS);

    assertSame(options, configured);
    assertEquals(5, configured.getTimeout());
    assertEquals(SECONDS, configured.getUnit());
    assertInstanceOf(com.datadog.openfeature.Provider.class, new Provider(configured));
  }
}
