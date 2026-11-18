package datadog.trace.logging.simplelogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.logging.LogLevel;
import datadog.trace.logging.PrintStreamWrapper;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class SLCompatSettingsTest {

  private static final List<String> NAMES = Arrays.asList("foo", "foo.bar", "foo.bar.baz");

  @TableTest({
    "scenario              | pStr | fStr  | dStr    | expected",
    "nothing set           |      |       |         |         ",
    "default only          |      |       | default | default ",
    "property only         | prop |       | default | prop    ",
    "property wins         | prop | fback | default | prop    ",
    "fallback over default |      | fback | default | fback   "
  })
  void testStringPropertyPrecedence(String pStr, String fStr, String dStr, String expected) {
    // when
    String name = "foo";
    Properties props = new Properties();
    Properties fallbackProps = new Properties();
    if (pStr != null) {
      props.setProperty(name, pStr);
    }
    if (fStr != null) {
      fallbackProps.setProperty(name, fStr);
    }

    // then
    assertEquals(expected, SLCompatSettings.getString(props, fallbackProps, name, dStr));
  }

  @TableTest({
    "scenario                   | pStr | fStr | dBool | expected",
    "default only               |      |      | true  | true    ",
    "property only              | true |      | false | true    ",
    "property wins              | true | foo  | false | true    ",
    "fallback over default      |      | true | false | true    ",
    "// any String not matching \"true\" is false               ",
    "non true property is false | foo  | true | true  | false   ",
    "// any String not matching \"true\" is false               ",
    "non true fallback is false |      | foo  | true  | false   "
  })
  void testBooleanPropertyPrecedence(String pStr, String fStr, boolean dBool, boolean expected) {
    // when
    String name = "foo";
    Properties props = new Properties();
    Properties fallbackProps = new Properties();
    if (pStr != null) {
      props.setProperty(name, pStr);
    }
    if (fStr != null) {
      fallbackProps.setProperty(name, fStr);
    }

    // then
    assertEquals(expected, SLCompatSettings.getBoolean(props, fallbackProps, name, dBool));
  }

  @Test
  void testDefaults() {
    // when
    SLCompatSettings settings = new SLCompatSettings(new Properties());

    // then
    assertNull(settings.warnLevelString);
    assertFalse(settings.levelInBrackets);
    assertEquals(System.err, ((PrintStreamWrapper) settings.printStream).getOriginalPrintStream());
    assertFalse(settings.showShortLogName);
    assertTrue(settings.showLogName);
    assertTrue(settings.showThreadName);
    assertEquals(SLCompatSettings.DiffDTFormatter.class, settings.dateTimeFormatter.getClass());
    assertFalse(settings.showDateTime);
    assertEquals(LogLevel.INFO, settings.defaultLogLevel);
    assertFalse(settings.jsonEnabled);
  }

  @Test
  void testFileProperties() {
    // when
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.CONFIGURATION_FILE, "slcompatsettingstest.properties");
    SLCompatSettings settings = new SLCompatSettings(props);
    StringBuilder formatted = new StringBuilder();
    int timeMillis = 4711 << 20;
    settings.dateTimeFormatter.appendFormattedDate(formatted, timeMillis, 1729);

    // then
    assertEquals("WRN", settings.warnLevelString);
    assertTrue(settings.levelInBrackets);
    assertEquals(System.out, ((PrintStreamWrapper) settings.printStream).getOriginalPrintStream());
    assertTrue(settings.showShortLogName);
    assertFalse(settings.showLogName);
    assertFalse(settings.showThreadName);
    assertEquals(
        new SimpleDateFormat("'['yy-dd-MM HH:mm:ss:SSS Z']'").format(new Date(timeMillis)),
        formatted.toString());
    assertTrue(settings.showDateTime);
    assertEquals(LogLevel.DEBUG, settings.defaultLogLevel);
    assertTrue(settings.jsonEnabled);
  }

  @Test
  void testLogFileCreation(@TempDir File dir) {
    // setup
    File file = new File(dir, "log");
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.LOG_FILE, file.getAbsolutePath());
    SLCompatSettings settings = new SLCompatSettings(props);
    try {
      // expect
      assertTrue(file.exists());
      assertNotEquals(System.err, settings.printStream);
      assertNotEquals(System.out, settings.printStream);
    } finally {
      // cleanup
      settings.printStream.close();
    }
  }

  @Test
  void testParentFolderOfLogFileCreation(@TempDir File dir) {
    // setup
    File parent = new File(dir, "parent");
    File file = new File(parent, "log");
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.LOG_FILE, file.getAbsolutePath());
    SLCompatSettings settings = new SLCompatSettings(props);
    try {
      // expect
      assertTrue(file.exists());
    } finally {
      // cleanup
      settings.printStream.close();
    }
  }

  @Test
  void testLogFileCreationStderrFallback(@TempDir File dir) {
    // setup
    dir.setWritable(false, true);
    try {
      File file = new File(dir, "log");
      Properties props = new Properties();
      props.setProperty(SLCompatSettings.Keys.LOG_FILE, file.getAbsolutePath());
      SLCompatSettings settings = new SLCompatSettings(props);

      // expect
      assertFalse(file.exists());
      assertEquals(
          System.err, ((PrintStreamWrapper) settings.printStream).getOriginalPrintStream());
    } finally {
      // cleanup
      dir.setWritable(true, true);
    }
  }

  @TableTest({
    "scenario       | showShort | show  | expected                   ",
    "no log name    | false     | false | ['', '', '']               ",
    "full log name  | false     | true  | [foo, foo.bar, foo.bar.baz]",
    "short log name | true      | true  | [foo, bar, baz]            ",
    "short wins     | true      | false | [foo, bar, baz]            "
  })
  void testLogNameForName(boolean showShort, boolean show, List<String> expected) {
    // when
    Properties props = new Properties();
    props.setProperty(SLCompatSettings.Keys.SHOW_SHORT_LOG_NAME, Boolean.toString(showShort));
    props.setProperty(SLCompatSettings.Keys.SHOW_LOG_NAME, Boolean.toString(show));
    SLCompatSettings settings = new SLCompatSettings(props);

    // then
    List<String> logNames = new ArrayList<>();
    for (String name : NAMES) {
      logNames.add(settings.logNameForName(name));
    }
    assertEquals(expected, logNames);
  }

  @TableTest({
    "scenario              | logProperties                       | expected             ",
    "no configuration      | [:]                                 | [INFO, INFO, INFO]   ",
    "root logger           | [foo: debug]                        | [DEBUG, DEBUG, DEBUG]",
    "intermediate logger   | [foo.bar: debug]                    | [INFO, DEBUG, DEBUG] ",
    "intermediate and leaf | [foo.bar: debug, foo.bar.baz: warn] | [INFO, DEBUG, WARN]  ",
    "unrelated and leaf    | [bar: trace, foo.bar.baz: warn]     | [INFO, INFO, WARN]   "
  })
  void testLogLevelForName(Map<String, String> logProperties, List<LogLevel> expected) {
    // when
    Properties props = new Properties();
    for (Map.Entry<String, String> entry : logProperties.entrySet()) {
      props.setProperty(SLCompatSettings.Keys.LOG_KEY_PREFIX + entry.getKey(), entry.getValue());
    }
    SLCompatSettings settings = new SLCompatSettings(props);

    // then
    List<LogLevel> logLevels = new ArrayList<>();
    for (String name : NAMES) {
      logLevels.add(settings.logLevelForName(name));
    }
    assertEquals(expected, logLevels);
  }

  @TableTest({
    "scenario         | legacy | pattern                 | timeMillis | startTimeMillis | expected",
    "relative         | false  |                         | 4711       | 1729            | 2982    ",
    "new formatter    | false  | 'yyyy-MM-dd HH:mm:ss Z' | 4711       | 1729            |         ",
    "legacy formatter | true   | 'yyyy-MM-dd HH:mm:ss z' | 4711       | 1729            |         "
  })
  void testDTFormatter(
      boolean legacy, String pattern, long timeMillis, long startTimeMillis, String expected) {
    // when
    SLCompatSettings.DTFormatter dtFormatter =
        legacy
            ? new SLCompatSettings.LegacyDTFormatter(pattern)
            : SLCompatSettings.DTFormatter.create(pattern);
    StringBuilder formatted = new StringBuilder();
    dtFormatter.appendFormattedDate(formatted, timeMillis, startTimeMillis);

    // then
    // a blank expected value means the date formatted with the pattern
    String expectedFormatted =
        expected != null ? expected : new SimpleDateFormat(pattern).format(new Date(timeMillis));
    assertEquals(expectedFormatted, formatted.toString());
  }
}
