package datadog.smoketest.spark;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.extension.RegisterExtension;

class SparkSpanLinksExperimentalSmokeTest extends AbstractSparkSpanLinksSmokeTest {
  @RegisterExtension static final SmokeCliApp APP = newApp("spark-span-links-ft", false, true);

  @Override
  SmokeCliApp app() {
    return APP;
  }

  @Override
  boolean spanLinksEnabled() {
    return true;
  }
}
