package datadog.trace.logging.simplelogger

import datadog.trace.logging.LogLevel
import datadog.trace.logging.PrintStreamWrapper
import spock.lang.Specification

import java.text.SimpleDateFormat

class SLCompatSettingsTest extends Specification {

  def "test String property precedence"() {
    when:
    def name = "foo"
    Properties props = new Properties()
    Properties fallbackProps = new Properties()
    if (pStr != null) {
      props.setProperty(name, pStr)
    }
    if (fStr != null) {
      fallbackProps.setProperty(name, fStr)
    }

    then:
    SLCompatSettings.getString(props, fallbackProps, name, dStr) == expected

    where:
    pStr   | fStr    | dStr      | expected
    null   | null    | null      | null
    null   | null    | "default" | "default"
    "prop" | null    | "default" | "prop"
    "prop" | "fback" | "default" | "prop"
    null   | "fback" | "default" | "fback"
  }

  def "test Boolean property precedence"() {
    when:
    def name = "foo"
    Properties props = new Properties()
    Properties fallbackProps = new Properties()
    if (pStr != null) {
      props.setProperty(name, pStr)
    }
    if (fStr != null) {
      fallbackProps.setProperty(name, fStr)
    }

    then:
    SLCompatSettings.getBoolean(props, fallbackProps, name, dBool) == expected

    where:
    pStr   | fStr   | dBool | expected
    null   | null   | true  | true
    "true" | null   | false | true
    "true" | "foo"  | false | true
    null   | "true" | false | true
    "foo"  | "true" | true  | false // any String not matching "true" is false
    null   | "foo"  | true  | false // any String not matching "true" is false
  }

  def "test defaults"() {
    when:
    SLCompatSettings settings = new SLCompatSettings(new Properties())

    then:
    settings.warnLevelString == null
    settings.levelInBrackets == false
    ((PrintStreamWrapper) settings.printStream).getOriginalPrintStream()  == System.err
    settings.showShortLogName == false
    settings.showLogName == true
    settings.showThreadName == true
    settings.dateTimeFormatter.class == SLCompatSettings.DiffDTFormatter
    settings.showDateTime == false
    settings.defaultLogLevel == LogLevel.INFO
    settings.jsonEnabled == false
  }

  def "test file properties"() {
    when:
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.CONFIGURATION_FILE, "slcompatsettingstest.properties")
    SLCompatSettings settings = new SLCompatSettings(props)
    StringBuilder formatted = new StringBuilder()
    settings.dateTimeFormatter.appendFormattedDate(formatted, 4711 << 20, 1729)

    then:
    settings.warnLevelString == "WRN"
    settings.levelInBrackets == true
    ((PrintStreamWrapper) settings.printStream).getOriginalPrintStream()  == System.out
    settings.showShortLogName == true
    settings.showLogName == false
    settings.showThreadName == false
    formatted.toString() == new SimpleDateFormat("'['yy-dd-MM HH:mm:ss:SSS Z']'").format(new Date(4711 << 20))
    settings.showDateTime == true
    settings.defaultLogLevel == LogLevel.DEBUG
    settings.jsonEnabled == true
  }

  def "test log file creation"() {
    setup:
    def dir = File.createTempDir()
    def file = new File(dir, "log")
    def props = new Properties()
    props.setProperty(SLCompatSettings.Keys.LOG_FILE, file.getAbsolutePath())
    def settings = new SLCompatSettings(props)

    expect:
    file.exists()
    settings.printStream != System.err
    settings.printStream != System.out

    cleanup:
    settings.printStream.close()
    dir.listFiles().each {
      it.delete()
    }
    dir.delete()
  }

  def "test parent folder of log file creation"() {
    setup:
    def dir = File.createTempDir()
    def parent = new File(dir, "parent")
    def file = new File(parent, "log")
    def props = new Properties()
    props.setProperty(SLCompatSettings.Keys.LOG_FILE, file.getAbsolutePath())
    def settings = new SLCompatSettings(props)

    expect:
    file.exists()

    cleanup:
    settings.printStream.close()
    dir.listFiles().each {
      it.delete()
    }
    dir.delete()
  }

  def "test log file creation stderr fallback"() {
    setup:
    def dir = File.createTempDir()
    dir.setWritable(false, true)
    def file = new File(dir, "log")
    def props = new Properties()
    props.setProperty(SLCompatSettings.Keys.LOG_FILE, file.getAbsolutePath())
    def settings = new SLCompatSettings(props)

    expect:
    !file.exists()
    ((PrintStreamWrapper) settings.printStream).getOriginalPrintStream() == System.err

    cleanup:
    dir.setWritable(true, true)
    dir.delete()
  }

  def "test logNameForName"() {
    when:
    def names = ["foo", "foo.bar", "foo.bar.baz"]
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.SHOW_SHORT_LOG_NAME, showShort.toString())
    props.setProperty(SLCompatSettings.Keys.SHOW_LOG_NAME, show.toString())
    def settings = new SLCompatSettings(props)

    then:
    names.collect { settings.logNameForName(it) } == expected

    where:
    showShort | show  | expected
    false     | false | ["", "", ""]
    false     | true  | ["foo", "foo.bar", "foo.bar.baz"]
    true      | true  | ["foo", "bar", "baz"]
    true      | false | ["foo", "bar", "baz"]
  }

  def "test logLevelForName"() {
    when:
    def names = ["foo", "foo.bar", "foo.bar.baz"]
    Properties props = new Properties()
    logProperties.each { props.setProperty(SLCompatSettings.Keys.LOG_KEY_PREFIX + it.key, it.value) }
    def settings = new SLCompatSettings(props)

    then:
    names.collect { settings.logLevelForName(it) } == expected

    where:
    logProperties                               | expected
    [:]                                         | [LogLevel.INFO, LogLevel.INFO, LogLevel.INFO]
    ["foo": "debug"]                            | [LogLevel.DEBUG, LogLevel.DEBUG, LogLevel.DEBUG]
    ["foo.bar": "debug"]                        | [LogLevel.INFO, LogLevel.DEBUG, LogLevel.DEBUG]
    ["foo.bar": "debug", "foo.bar.baz": "warn"] | [LogLevel.INFO, LogLevel.DEBUG, LogLevel.WARN]
    ["bar": "trace", "foo.bar.baz": "warn"]     | [LogLevel.INFO, LogLevel.INFO, LogLevel.WARN]
  }

  def "test DTFormatter #iterationIndex"() {
    when:
    def formatted = new StringBuilder()
    dtFormatter.appendFormattedDate(formatted, timeMillis, startTimeMillis)

    then:
    formatted.toString() == expected

    where:
    dtFormatter                                                     | timeMillis | startTimeMillis | expected
    SLCompatSettings.DTFormatter.create(null)                       | 4711       | 1729            | "2982"
    SLCompatSettings.DTFormatter.create("yyyy-MM-dd HH:mm:ss Z")    | 4711       | 1729            | "${new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(new Date(4711))}"
    new SLCompatSettings.LegacyDTFormatter("yyyy-MM-dd HH:mm:ss z") | 4711       | 1729            | "${new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new Date(4711))}"
  }
}
