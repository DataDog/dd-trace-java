import datadog.trace.agent.test.utils.UnsafeUtils


class Log4jThreadContextWithEnableThreadLocalsTest extends Log4jThreadContextTest {
  static {
    //TODO: set ("log4j2.is.webapp" = "true") from "log4j2.component.properties" file instead of:
    UnsafeUtils.setStaticBooleanField(
      Class.forName("org.apache.logging.log4j.util.Constants")
        .getField("ENABLE_THREADLOCALS"),
      true
    )
  }
}
