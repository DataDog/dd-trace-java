package datadog.smoketest.spark;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.extension.RegisterExtension;

class SparkSpanLinksBothDisabledSmokeTest extends AbstractSparkSpanLinksSmokeTest {
  @RegisterExtension static final SmokeCliApp APP = newApp("spark-span-links-ff", false, false);

  @Override
  SmokeCliApp app() {
    return APP;
  }

  @Override
  boolean spanLinksEnabled() {
    return false;
  }
}
