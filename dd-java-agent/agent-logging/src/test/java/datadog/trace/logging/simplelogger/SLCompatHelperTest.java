package datadog.trace.logging.simplelogger;

import static datadog.trace.logging.LogLevel.DEBUG;
import static datadog.trace.logging.LogLevel.ERROR;
import static datadog.trace.logging.LogLevel.INFO;
import static datadog.trace.logging.LogLevel.OFF;
import static datadog.trace.logging.LogLevel.TRACE;
import static datadog.trace.logging.LogLevel.WARN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.NoStackException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.tabletest.junit.TableTest;

class SLCompatHelperTest {

  /** Placeholder in expected outputs replaced by the formatted date of timestamp 4711. */
  private static final String DATE_PLACEHOLDER = "<date>";

  private static final String THREAD = Thread.currentThread().getName();

  @TableTest({
    "scenario | level | trace | debug | info  | warn  | error | off ",
    "trace    | trace | true  | true  | true  | true  | true  | true",
    "debug    | debug | false | true  | true  | true  | true  | true",
    "info     | info  | false | false | true  | true  | true  | true",
    "warn     | warn  | false | false | false | true  | true  | true",
    "error    | error | false | false | false | false | true  | true",
    "off      | off   | false | false | false | false | false | true"
  })
  void testLevelEnabled(
      String level,
      boolean trace,
      boolean debug,
      boolean info,
      boolean warn,
      boolean error,
      boolean off) {
    // when
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, level);
    SLCompatHelper helper = new SLCompatHelper("foo", new SLCompatSettings(props));

    // then
    assertEnabled(helper, trace, debug, info, warn, error, off);
  }

  @TableTest({
    "scenario                 | name        | trace | debug | info  | warn | error | off ",
    "default level            | foo         | false | false | true  | true | true  | true",
    "configured logger        | foo.bar     | false | true  | true  | true | true  | true",
    "configured nested logger | foo.bar.baz | false | false | false | true | true  | true"
  })
  void testLevelEnabledLogLevelForName(
      String name,
      boolean trace,
      boolean debug,
      boolean info,
      boolean warn,
      boolean error,
      boolean off) {
    // when
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.LOG_KEY_PREFIX + "foo.bar", "debug");
    props.setProperty(SLCompatSettings.Keys.LOG_KEY_PREFIX + "foo.bar.baz", "warn");
    SLCompatHelper helper = new SLCompatHelper(name, new SLCompatSettings(props));

    // then
    assertEnabled(helper, trace, debug, info, warn, error, off);
  }

  private static void assertEnabled(
      SLCompatHelper helper,
      boolean trace,
      boolean debug,
      boolean info,
      boolean warn,
      boolean error,
      boolean off) {
    assertEquals(trace, helper.enabled(TRACE, null));
    assertEquals(debug, helper.enabled(DEBUG, null));
    assertEquals(info, helper.enabled(INFO, null));
    assertEquals(warn, helper.enabled(WARN, null));
    assertEquals(error, helper.enabled(ERROR, null));
    assertEquals(off, helper.enabled(OFF, null));
  }

  @TableTest({
    "scenario | name | level | msg   | expected         ",
    "trace    | foo  | TRACE | who   | TRACE foo - who  ",
    "debug    | bar  | DEBUG | when  | DEBUG bar - when ",
    "info     | baz  | INFO  | why   | INFO baz - why   ",
    "warn     | biz  | WARN  | what  | WARN biz - what  ",
    "error    | buz  | ERROR | srsly | ERROR buz - srsly"
  })
  void testDefaultLoggingFormat(String name, LogLevel level, String msg, String expected) {
    // when
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream printStream = new PrintStream(outputStream, true);
    SLCompatSettings settings =
        new SLCompatSettings(new Properties(), new Properties(), printStream);
    SLCompatHelper helper = new SLCompatHelper(name, settings);
    helper.log(level, null, msg, null);

    // then
    assertEquals("[" + THREAD + "] " + expected + "\n", outputStream.toString());
  }

  @Test
  void testDefaultLoggingFormatWithException() {
    // setup
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream printStream = new PrintStream(outputStream, true);
    SLCompatSettings settings =
        new SLCompatSettings(new Properties(), new Properties(), printStream);
    SLCompatHelper helper = new SLCompatHelper("foo", settings);
    NoStackException exception = new NoStackException("wrong");
    helper.log(ERROR, null, "log", exception);

    // expect
    assertEquals(
        "[" + THREAD + "] ERROR foo - log\n" + NoStackException.class.getName() + ": wrong\n",
        outputStream.toString());
  }

  // remove LogLevel.OFF
  @ParameterizedTest
  @EnumSource(value = LogLevel.class, names = "OFF", mode = EnumSource.Mode.EXCLUDE)
  void testLoggingWithAnEmbeddedExceptionInTheMessage(LogLevel level) {
    // setup
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream printStream = new PrintStream(outputStream, true);
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.EMBED_EXCEPTION, "true");
    SLCompatSettings settings = new SLCompatSettings(props, new Properties(), printStream);
    SLCompatHelper helper = new SLCompatHelper("foo", settings);
    try {
      throw new IOException("wrong");
    } catch (Exception exception) {
      helper.log(level, null, "log", exception);
    }

    // expect
    String output = outputStream.toString();
    assertTrue(
        output.matches(
            "^.* "
                + level
                + " foo - log \\[exception:java\\.io\\.IOException: wrong\\. at .*\\]\n$"),
        output);
  }

  @Test
  void testLoggingWithoutThreadNameAndWithTime() {
    // setup
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream printStream = new PrintStream(outputStream, true);
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false");
    props.setProperty(SLCompatSettings.Keys.SHOW_DATE_TIME, "true");
    SLCompatSettings settings = new SLCompatSettings(props, new Properties(), printStream);
    SLCompatHelper helper = new SLCompatHelper("foo", settings);
    helper.log(ERROR, null, "log", null);

    // expect
    String output = outputStream.toString();
    assertTrue(output.matches("^\\d+ ERROR foo - log\n$"), output);
  }

  @TableTest({
    "scenario                   | level | warnS  | showB | showS | showL | showT | dateTFS                 | showDT | jsonE | expected                  ",
    "default warn               | WARN  |        | false | false | false | false |                         | false  | false | WARN log                  ",
    "custom warn string         | WARN  | DANGER | false | false | false | false |                         | false  | false | DANGER log                ",
    "custom warn string on info | INFO  | DANGER | false | false | false | false |                         | false  | false | INFO log                  ",
    "level in brackets          | WARN  |        | true  | false | false | false |                         | false  | false | '[WARN] log'              ",
    "short log name             | INFO  |        | false | true  | false | false |                         | false  | false | INFO bar - log            ",
    "short log name wins        | INFO  |        | true  | true  | true  | false |                         | false  | false | '[INFO] bar - log'        ",
    "log name                   | INFO  |        | true  | false | true  | false |                         | false  | false | '[INFO] foo.bar - log'    ",
    "thread name                | INFO  |        | false | false | false | true  |                         | false  | false | '[thread] INFO log'       ",
    "relative date time         | INFO  |        | false | false | false | true  |                         | true   | false | '4711 [thread] INFO log'  ",
    "date format without time   | INFO  |        | false | false | false | true  | 'yyyy-MM-dd HH:mm:ss z' | false  | false | '[thread] INFO log'       ",
    "date format with time      | INFO  |        | false | false | false | true  | 'yyyy-MM-dd HH:mm:ss z' | true   | false | '<date> [thread] INFO log'"
  })
  void testLogOutputWithConfiguration(
      LogLevel level,
      String warnS,
      boolean showB,
      boolean showS,
      boolean showL,
      boolean showT,
      String dateTFS,
      boolean showDT,
      boolean jsonE,
      String expected) {
    // when
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    SLCompatHelper helper =
        createHelper(outputStream, warnS, showB, showS, showL, showT, dateTFS, showDT, jsonE);

    helper.log(level, null, 0, 4711, "thread", "log", null);

    // then
    assertEquals(expectedOutput(expected, dateTFS), outputStream.toString());
  }

  @TableTest({
    "scenario      | level | warnS  | showB | showS | showL | showT | dateTFS | showDT | jsonE | expected                                                            ",
    "json disabled | WARN  |        | false | false | false | false |         | false  | false | WARN log                                                            ",
    "json enabled  | WARN  | DANGER | false | false | false | false |         | false  | true  | '{\"origin\":\"dd.trace\",\"level\":\"DANGER\",\"message\":\"log\"}'"
  })
  void testLogOutputWithJsonConfigurationKey(
      LogLevel level,
      String warnS,
      boolean showB,
      boolean showS,
      boolean showL,
      boolean showT,
      String dateTFS,
      boolean showDT,
      boolean jsonE,
      String expected) {
    // when
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    SLCompatHelper helper =
        createHelper(outputStream, warnS, showB, showS, showL, showT, dateTFS, showDT, jsonE);

    // helper.log is where we split between logs and JSON logs
    helper.log(level, null, "log", null);

    // then
    assertEquals(expected + "\n", outputStream.toString());
  }

  @TableTest({
    "scenario                   | level | warnS  | showB | showS | showL | showT | dateTFS                 | showDT | jsonE | expected                                                                                                                ",
    "custom warn string         | WARN  | DANGER | false | false | false | false |                         | false  | true  | '{\"origin\":\"dd.trace\",\"level\":\"DANGER\",\"message\":\"log\"}'                                                    ",
    "custom warn string on info | INFO  | DANGER | false | false | false | false |                         | false  | true  | '{\"origin\":\"dd.trace\",\"level\":\"INFO\",\"message\":\"log\"}'                                                      ",
    "level in brackets          | WARN  |        | true  | false | false | false |                         | false  | true  | '{\"origin\":\"dd.trace\",\"level\":\"[WARN]\",\"message\":\"log\"}'                                                    ",
    "short log name             | INFO  |        | false | true  | false | false |                         | false  | true  | '{\"origin\":\"dd.trace\",\"level\":\"INFO\",\"logger.name\":\"bar\",\"message\":\"log\"}'                              ",
    "short log name wins        | INFO  |        | true  | true  | true  | false |                         | false  | true  | '{\"origin\":\"dd.trace\",\"level\":\"[INFO]\",\"logger.name\":\"bar\",\"message\":\"log\"}'                            ",
    "log name                   | INFO  |        | true  | false | true  | false |                         | false  | true  | '{\"origin\":\"dd.trace\",\"level\":\"[INFO]\",\"logger.name\":\"foo.bar\",\"message\":\"log\"}'                        ",
    "thread name                | INFO  |        | false | false | false | true  |                         | false  | true  | '{\"origin\":\"dd.trace\",\"logger.thread_name\":\"thread\",\"level\":\"INFO\",\"message\":\"log\"}'                    ",
    "date format without time   | INFO  |        | false | false | false | true  | 'yyyy-MM-dd HH:mm:ss z' | false  | true  | '{\"origin\":\"dd.trace\",\"logger.thread_name\":\"thread\",\"level\":\"INFO\",\"message\":\"log\"}'                    ",
    "date format with time      | INFO  |        | false | false | false | true  | 'yyyy-MM-dd HH:mm:ss z' | true   | true  | '{\"origin\":\"dd.trace\",\"date\":\"<date>\",\"logger.thread_name\":\"thread\",\"level\":\"INFO\",\"message\":\"log\"}'"
  })
  void testLogOutputInJson(
      LogLevel level,
      String warnS,
      boolean showB,
      boolean showS,
      boolean showL,
      boolean showT,
      String dateTFS,
      boolean showDT,
      boolean jsonE,
      String expected) {
    // when
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    SLCompatHelper helper =
        createHelper(outputStream, warnS, showB, showS, showL, showT, dateTFS, showDT, jsonE);

    helper.logJson(level, null, 0, 4711, "thread", "log", null);

    // then
    assertEquals(expectedOutput(expected, dateTFS), outputStream.toString());
  }

  @Test
  void testLoggingWithAnEmbeddedExceptionInJson() {
    // setup
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream printStream = new PrintStream(outputStream, true);
    Properties props = new Properties();
    SLCompatSettings.DTFormatter dateTimeFormatter =
        SLCompatSettings.DTFormatter.create("yyyy-MM-dd HH:mm:ss z");
    SLCompatSettings settings =
        new SLCompatSettings(
            props,
            props,
            null,
            false,
            printStream,
            false,
            true,
            false,
            dateTimeFormatter,
            false,
            true,
            INFO,
            true);
    SLCompatHelper helper = new SLCompatHelper("foo", settings);
    try {
      throw new IOException("wrong");
    } catch (Exception exception) {
      helper.log(INFO, null, "log", exception);
    }

    // expect
    String output = outputStream.toString();
    assertTrue(
        output.matches(
            "^\\{\"origin\":\"dd.trace\",\"level\":\"INFO\",\"logger.name\":\"foo\",\"message\":\"log\",\"exception\":\\{\"message\":\"wrong\",\"stackTrace\":\\[.*\\]\\}\\}\n$"),
        output);
  }

  private static SLCompatHelper createHelper(
      ByteArrayOutputStream outputStream,
      String warnS,
      boolean showB,
      boolean showS,
      boolean showL,
      boolean showT,
      String dateTFS,
      boolean showDT,
      boolean jsonE) {
    PrintStream printStream = new PrintStream(outputStream, true);
    Properties props = new Properties();
    SLCompatSettings.DTFormatter dateTimeFormatter = SLCompatSettings.DTFormatter.create(dateTFS);
    SLCompatSettings settings =
        new SLCompatSettings(
            props,
            props,
            warnS,
            showB,
            printStream,
            showS,
            showL,
            showT,
            dateTimeFormatter,
            showDT,
            jsonE,
            INFO,
            false);
    return new SLCompatHelper("foo.bar", settings);
  }

  /** Replaces the date placeholder with the timestamp 4711 formatted with the given pattern. */
  private static String expectedOutput(String expected, String dateTFS) {
    if (expected.contains(DATE_PLACEHOLDER)) {
      String formattedDate = new SimpleDateFormat(dateTFS).format(new Date(4711));
      expected = expected.replace(DATE_PLACEHOLDER, formattedDate);
    }
    return expected + "\n";
  }
}
