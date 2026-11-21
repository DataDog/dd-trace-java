package datadog.smoketest.spark;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.extension.RegisterExtension;

class SparkSpanLinksUnsetSmokeTest extends AbstractSparkSpanLinksSmokeTest {
  @RegisterExtension static final SmokeCliApp APP = newApp("spark-span-links-unset", null, null);

  @Override
  SmokeCliApp app() {
    return APP;
  }

  @Override
  boolean spanLinksEnabled() {
    return false;
  }
}
