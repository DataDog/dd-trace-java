package datadog.smoketest;

import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.util.TagsHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpleAppDebuggerIntegrationTest extends BaseIntegrationTest {
  protected static final Logger LOG =
      LoggerFactory.getLogger(SimpleAppDebuggerIntegrationTest.class);
  protected static final String DEBUGGER_TEST_APP_CLASS =
      "datadog.smoketest.debugger.DebuggerTestApplication";
  protected static final ProbeId PROBE_ID = new ProbeId("123356536", 0);
  protected static final ProbeId PROBE_ID2 = new ProbeId("1233565368", 12);
  protected static final String MAIN_CLASS_NAME = "Main";

  @Override
  protected String getAppClass() {
    return DEBUGGER_TEST_APP_CLASS;
  }

  @Override
  protected String getAppId() {
    return TagsHelper.sanitize("DebuggerTestApplication");
  }
}
