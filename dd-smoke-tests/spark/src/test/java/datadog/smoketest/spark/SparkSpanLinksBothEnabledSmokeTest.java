package datadog.smoketest.spark;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.extension.RegisterExtension;

class SparkSpanLinksBothEnabledSmokeTest extends AbstractSparkSpanLinksSmokeTest {
  @RegisterExtension static final SmokeCliApp APP = newApp("spark-span-links-tt", true, true);

  @Override
  SmokeCliApp app() {
    return APP;
  }

  @Override
  boolean spanLinksEnabled() {
    return true;
  }
}
