package datadog.trace.logging.simplelogger

import datadog.trace.logging.LogLevel
import spock.lang.Shared
import spock.lang.Specification

import java.text.SimpleDateFormat

class SLCompatHelperTest extends Specification {

  private class NoStackException extends Exception {
    NoStackException(String message) {
      super(message, null, false, false)
    }
  }

  def "test levelEnabled"() {
    when:
    Properties props = new Properties()
    props.setProperty(SLCompatSettings.Keys.DEFAULT_LOG_LEVEL, level)
    def helper = new SLCompatHelper("foo", new SLCompatSettings(props))

    then:
    helper.enabled(LogLevel.TRACE, null) == trace
    helper.enabled(LogLevel.DEBUG, null) == debug
    helper.enabled(LogLevel.INFO, null) == info
    helper.enabled(LogLevel.WARN, null) == warn
    helper.enabled(LogLevel.ERROR, null) == error
    helper.enabled(LogLevel.OFF, null) == off

    where:
    level   | trace | debug | info  | warn  | error | off
    "trace" | true  | true  | true  | true  | true  | true
    "debug" | false | true  | true  | true  | true  | true
    "info"  | false | false | true  | true  | true  | true
    "warn"  | false | false | false | true  | true  | true
    "error" | false | false | false | false | true  | true
    "off"   | false | false | false | false | false | true
  }

  def "test levelEnabled logLevelForName"() {
    when:
    Properties props = new Properties()
    ["foo.bar": "debug", "foo.bar.baz": "warn"].each {
      props.setProperty(SLCompatSettings.Keys.LOG_KEY_PREFIX + it.key, it.value)
    }
    def helper = new SLCompatHelper(name, new SLCompatSettings(props))

    then:
    helper.enabled(LogLevel.TRACE, null) == trace
    helper.enabled(LogLevel.DEBUG, null) == debug
    helper.enabled(LogLevel.INFO, null) == info
    helper.enabled(LogLevel.WARN, null) == warn
    helper.enabled(LogLevel.ERROR, null) == error
    helper.enabled(LogLevel.OFF, null) == off

    where:
    name          | trace | debug | info  | warn | error | off
    "foo"         | false | false | true  | true | true  | true
    "foo.bar"     | false | true  | true  | true | true  | true
    "foo.bar.baz" | false | false | false | true | true  | true
  }

  @Shared
  def thread = Thread.currentThread().getName()

  def "test default logging format"() {
    when:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def settings = new SLCompatSettings(new Properties(), new Properties(), printStream)
    def helper = new SLCompatHelper(name, settings)
    helper.log(level, null, msg, null)

    then:
    outputStream.toString() == expected

    where:
    name  | level          | msg     | expected
    "foo" | LogLevel.TRACE | "who"   | "[$thread] TRACE foo - who\n"
    "bar" | LogLevel.DEBUG | "when"  | "[$thread] DEBUG bar - when\n"
    "baz" | LogLevel.INFO  | "why"   | "[$thread] INFO baz - why\n"
    "biz" | LogLevel.WARN  | "what"  | "[$thread] WARN biz - what\n"
    "buz" | LogLevel.ERROR | "srsly" | "[$thread] ERROR buz - srsly\n"
  }

  def "test default logging format with exception"() {
    setup:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def settings = new SLCompatSettings(new Properties(), new Properties(), printStream)
    def helper = new SLCompatHelper("foo", settings)
    def exception = new NoStackException("wrong")
    helper.log(LogLevel.ERROR, null, "log", exception)

    expect:
    outputStream.toString() == "[$thread] ERROR foo - log\n${NoStackException.getName()}: wrong\n"
  }

  def "test logging with an embedded exception in the message"() {
    setup:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def props = new Properties()
    props.setProperty(SLCompatSettings.Keys.EMBED_EXCEPTION, "true")
    def settings = new SLCompatSettings(props, new Properties(), printStream)
    def helper = new SLCompatHelper("foo", settings)
    try {
      throw new IOException("wrong")
    } catch(Exception exception) {
      helper.log(level, null, "log", exception)
    }

    expect:
    outputStream.toString() ==~ /^.* $level foo - log \[exception:java\.io\.IOException: wrong\. at .*\]\n$/

    where:
    level << LogLevel.values().toList().take(5) // remove LogLevel.OFF
  }

  def "test logging without thread name and with time"() {
    setup:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def props = new Properties()
    props.setProperty(SLCompatSettings.Keys.SHOW_THREAD_NAME, "false")
    props.setProperty(SLCompatSettings.Keys.SHOW_DATE_TIME, "true")
    def settings = new SLCompatSettings(props, new Properties(), printStream)
    def helper = new SLCompatHelper("foo", settings)
    helper.log(LogLevel.ERROR, null, "log", null)

    expect:
    outputStream.toString() ==~ /^\d+ ERROR foo - log\n$/
  }

  def "test log output with configuration"() {
    when:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def props = new Properties()
    def dateTimeFormatter = SLCompatSettings.DTFormatter.create(dateTFS)
    def settings = new SLCompatSettings(props, props, warnS, showB, printStream, showS, showL, showT, dateTimeFormatter, showDT, jsonE, LogLevel.INFO, false)
    def helper = new SLCompatHelper("foo.bar", settings)

    helper.log(level, null, 0, 4711, "thread", "log", null)

    then:
    outputStream.toString() == expected

    where:
    level         | warnS    | showB | showS | showL | showT | dateTFS                 | showDT |  jsonE | expected
    LogLevel.WARN | null     | false | false | false | false | null                    | false  |  false | "WARN log\n"
    LogLevel.WARN | "DANGER" | false | false | false | false | null                    | false  |  false | "DANGER log\n"
    LogLevel.INFO | "DANGER" | false | false | false | false | null                    | false  |  false | "INFO log\n"
    LogLevel.WARN | null     | true  | false | false | false | null                    | false  |  false | "[WARN] log\n"
    LogLevel.INFO | null     | false | true  | false | false | null                    | false  |  false | "INFO bar - log\n"
    LogLevel.INFO | null     | true  | true  | true  | false | null                    | false  |  false | "[INFO] bar - log\n"
    LogLevel.INFO | null     | true  | false | true  | false | null                    | false  |  false | "[INFO] foo.bar - log\n"
    LogLevel.INFO | null     | false | false | false | true  | null                    | false  |  false | "[thread] INFO log\n"
    LogLevel.INFO | null     | false | false | false | true  | null                    | true   |  false | "4711 [thread] INFO log\n"
    LogLevel.INFO | null     | false | false | false | true  | "yyyy-MM-dd HH:mm:ss z" | false  |  false | "[thread] INFO log\n"
    LogLevel.INFO | null     | false | false | false | true  | "yyyy-MM-dd HH:mm:ss z" | true   |  false | "${new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(new Date(4711))} [thread] INFO log\n"
  }



  def "test log output with Json configuration key"() {
    when:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def props = new Properties()
    def dateTimeFormatter = SLCompatSettings.DTFormatter.create(dateTFS)
    def settings = new SLCompatSettings(props, props, warnS, showB, printStream, showS, showL, showT, dateTimeFormatter, showDT, jsonE, LogLevel.INFO, false)
    def helper = new SLCompatHelper("foo.bar", settings)

    // helper.log is where we split between logs and JSON logs
    helper.log(level, null, "log", null)

    then:
    outputStream.toString() == expected

    where:
    level         | warnS    | showB | showS | showL | showT | dateTFS                 | showDT |  jsonE | expected
    LogLevel.WARN | null     | false | false | false | false | null                    | false  |  false | "WARN log\n"
    LogLevel.WARN | "DANGER" | false | false | false | false | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"level\":\"DANGER\",\"message\":\"log\"}\n"
  }

  def "test log output in Json"() {
    when:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def props = new Properties()
    def dateTimeFormatter = SLCompatSettings.DTFormatter.create(dateTFS)
    def settings = new SLCompatSettings(props, props, warnS, showB, printStream, showS, showL, showT, dateTimeFormatter, showDT, jsonE, LogLevel.INFO, false)
    def helper = new SLCompatHelper("foo.bar", settings)

    helper.logJson(level,null,0,4711,"thread","log", null)

    then:
    outputStream.toString() == expected

    where:
    level         | warnS    | showB | showS | showL | showT | dateTFS                 | showDT |  jsonE | expected
    LogLevel.WARN | "DANGER" | false | false | false | false | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"level\":\"DANGER\",\"message\":\"log\"}\n"
    LogLevel.INFO | "DANGER" | false | false | false | false | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"level\":\"INFO\",\"message\":\"log\"}\n"
    LogLevel.WARN | null     | true  | false | false | false | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"level\":\"[WARN]\",\"message\":\"log\"}\n"
    LogLevel.INFO | null     | false | true  | false | false | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"level\":\"INFO\",\"logger.name\":\"bar\",\"message\":\"log\"}\n"
    LogLevel.INFO | null     | true  | true  | true  | false | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"level\":\"[INFO]\",\"logger.name\":\"bar\",\"message\":\"log\"}\n"
    LogLevel.INFO | null     | true  | false | true  | false | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"level\":\"[INFO]\",\"logger.name\":\"foo.bar\",\"message\":\"log\"}\n"
    LogLevel.INFO | null     | false | false | false | true  | null                    | false  |  true  | "{\"origin\":\"dd.trace\",\"logger.thread_name\":\"thread\",\"level\":\"INFO\",\"message\":\"log\"}\n"
    LogLevel.INFO | null     | false | false | false | true  | "yyyy-MM-dd HH:mm:ss z" | false  |  true  | "{\"origin\":\"dd.trace\",\"logger.thread_name\":\"thread\",\"level\":\"INFO\",\"message\":\"log\"}\n"
    LogLevel.INFO | null     | false | false | false | true  | "yyyy-MM-dd HH:mm:ss z" | true   |  true  | "{\"origin\":\"dd.trace\",\"date\":\"${new SimpleDateFormat(dateTFS).format(new Date(4711))}\",\"logger.thread_name\":\"thread\",\"level\":\"INFO\",\"message\":\"log\"}\n"
  }


  def "test logging with an embedded exception in Json"() {
    setup:
    def outputStream = new ByteArrayOutputStream()
    def printStream = new PrintStream(outputStream, true)
    def props = new Properties()
    def dateTimeFormatter = SLCompatSettings.DTFormatter.create("yyyy-MM-dd HH:mm:ss z")
    def settings = new SLCompatSettings(props, props, null, false, printStream, false,true,false, dateTimeFormatter, false, true, LogLevel.INFO, true)
    def helper = new SLCompatHelper("foo", settings)
    try {
      throw new IOException("wrong")
    } catch(Exception exception) {
      helper.log(LogLevel.INFO, null, "log", exception)
    }
    expect:
    outputStream.toString() ==~ /^\{"origin":"dd.trace","level":"INFO","logger.name":"foo","message":"log","exception":\{"message":"wrong","stackTrace":\[.*\]\}\}\n$/
  }
}
