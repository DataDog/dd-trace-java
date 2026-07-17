package datadog.trace.api;

import static datadog.trace.api.config.CiVisibilityConfig.CODE_COVERAGE_FLAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.trace.junit.utils.config.WithConfigExtension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

@ExtendWith(WithConfigExtension.class)
class ConfigCodeCoverageFlagsTest {

  @Test
  void defaultsToNoFlags() {
    assertEquals(Collections.emptyList(), Config.get().getCodeCoverageFlags());
  }

  @Test
  void removesWhitespaceAndEmptyFlagsWhilePreservingOrderAndDuplicates() {
    Config config = configWithFlags(" type:unit-tests, ,jvm-21,, type:unit-tests ");

    assertEquals(
        Arrays.asList("type:unit-tests", "jvm-21", "type:unit-tests"),
        config.getCodeCoverageFlags());
  }

  @Test
  void readsFlagsFromCanonicalEnvironmentVariable() {
    WithConfigExtension.injectEnvConfig("DD_CODE_COVERAGE_FLAGS", "type:unit-tests,jvm-21", false);

    assertEquals(Arrays.asList("type:unit-tests", "jvm-21"), Config.get().getCodeCoverageFlags());
  }

  @Test
  void producesNoFlagsForEmptyInput() {
    assertEquals(Collections.emptyList(), configWithFlags(" , , ").getCodeCoverageFlags());
  }

  @Test
  void acceptsExactlyThirtyTwoFlagsAndReturnsImmutableSnapshot() {
    List<String> expectedFlags = flags(32);
    Config config = configWithFlags(String.join(",", expectedFlags));

    assertEquals(expectedFlags, config.getCodeCoverageFlags());
    assertThrows(
        UnsupportedOperationException.class, () -> config.getCodeCoverageFlags().add("extra"));
  }

  @Test
  void omitsAllFlagsWhenMoreThanThirtyTwoAreConfigured() {
    Logger logger = (Logger) LoggerFactory.getLogger(Config.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      Config config = configWithFlags(String.join(",", flags(33)));

      assertEquals(Collections.emptyList(), config.getCodeCoverageFlags());
      List<ILoggingEvent> overflowWarnings = new ArrayList<>();
      for (ILoggingEvent event : appender.list) {
        if (event.getLevel() == Level.WARN
            && event.getFormattedMessage().contains("code coverage report flags")) {
          overflowWarnings.add(event);
        }
      }
      assertEquals(1, overflowWarnings.size());
      assertTrue(overflowWarnings.get(0).getFormattedMessage().contains("33"));
      assertTrue(overflowWarnings.get(0).getFormattedMessage().contains("32"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  private static Config configWithFlags(String flags) {
    WithConfigExtension.injectSysConfig(CODE_COVERAGE_FLAGS, flags);
    return Config.get();
  }

  private static List<String> flags(int count) {
    List<String> flags = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      flags.add("flag-" + i);
    }
    return flags;
  }
}
