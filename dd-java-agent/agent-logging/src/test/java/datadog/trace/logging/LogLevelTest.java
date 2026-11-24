package datadog.trace.logging;

import static datadog.trace.logging.LogLevel.DEBUG;
import static datadog.trace.logging.LogLevel.ERROR;
import static datadog.trace.logging.LogLevel.INFO;
import static datadog.trace.logging.LogLevel.OFF;
import static datadog.trace.logging.LogLevel.TRACE;
import static datadog.trace.logging.LogLevel.WARN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.tabletest.junit.TableTest;

class LogLevelTest {

  @TableTest({
    "scenario         | str   | level",
    "trace            | trace | TRACE",
    "debug            | debug | DEBUG",
    "info             | info  | INFO ",
    "warn             | warn  | WARN ",
    "error            | error | ERROR",
    "off              | off   | OFF  ",
    "unknown fallback | foo   | INFO "
  })
  void testFromString(String str, LogLevel level) {
    // when
    LogLevel fromString = LogLevel.fromString(str);

    // then
    assertEquals(level, fromString);
  }

  @TableTest({
    "scenario | level | trace | debug | info  | warn  | error | off ",
    "trace    | TRACE | true  | true  | true  | true  | true  | true",
    "debug    | DEBUG | false | true  | true  | true  | true  | true",
    "info     | INFO  | false | false | true  | true  | true  | true",
    "warn     | WARN  | false | false | false | true  | true  | true",
    "error    | ERROR | false | false | false | false | true  | true",
    "off      | OFF   | false | false | false | false | false | true"
  })
  void testIsEnabled(
      LogLevel level,
      boolean trace,
      boolean debug,
      boolean info,
      boolean warn,
      boolean error,
      boolean off) {
    // expect
    assertEquals(trace, TRACE.isEnabled(level));
    assertEquals(debug, DEBUG.isEnabled(level));
    assertEquals(info, INFO.isEnabled(level));
    assertEquals(warn, WARN.isEnabled(level));
    assertEquals(error, ERROR.isEnabled(level));
    assertEquals(off, OFF.isEnabled(level));
  }
}
