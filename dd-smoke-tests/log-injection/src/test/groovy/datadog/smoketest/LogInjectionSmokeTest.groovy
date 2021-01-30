package datadog.smoketest

import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

/**
 * Each smoketest application is expected to log four lines to the log file:
 * - BEFORE FIRST SPAN
 * - INSIDE FIRST SPAN
 * - AFTER FIRST SPAN
 * - INSIDE SECOND SPAN
 *
 * Additional, each application prints to std out:
 * FIRSTTRACEID TRACEID SPANID
 * SECONDTRACEID TRACEID SPANID
 */
abstract class LogInjectionSmokeTest extends AbstractSmokeTest {
  // Estimate for the amount of time instrumentation, plus request, plus some extra
  static final int TIMEOUT_SECS = 60

  @Shared
  File outputLogFile

  @Override
  ProcessBuilder createProcessBuilder() {
    def loggingJar = buildDirectory + "/libs/" + getClass().simpleName + ".jar"

    assert new File(loggingJar).isFile()

    outputLogFile = File.createTempFile("logTest", ".log")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.add("-Ddd.test.logfile=${outputLogFile.absolutePath}" as String)
    command.add("-Ddd.logs.injection=true")
    command.addAll(additionalArguments())
    command.addAll((String[]) ["-jar", loggingJar])

    println  "COMMANDS: " + command.join(" ")
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  List additionalArguments() {
    return []
  }

  abstract backend()

  abstract assertLogLines(List<String> logLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId)

  def cleanupSpec() {
    outputLogFile?.delete()
  }

  // TODO: once java7 support is dropped use waitFor(timeout)
  @Timeout(value = TIMEOUT_SECS, unit = TimeUnit.SECONDS)
  def "check raw file injection"() {
    when:
    def exitValue = testedProcess.waitFor()
    def count = waitForTraceCount(2)
    def logLines = outputLogFile.readLines()
    def stdOutLines = new File(logFilePath).readLines()
    def (_1, String firstTraceId, String firstSpanId) = stdOutLines.find { it.startsWith("FIRSTTRACEID")}.split(" ")
    def (_2, String secondTraceId, String secondSpanId) = stdOutLines.find { it.startsWith("SECONDTRACEID")}.split(" ")
    println "log lines: " + logLines

    then:
    exitValue == 0
    count == 2
    firstTraceId && firstTraceId != "0"
    firstSpanId && firstSpanId != "0"
    secondTraceId && secondTraceId != "0"
    secondSpanId && secondSpanId != "0"

    // Assert log line starts with backend name.
    // This avoids inadvertant passing because the incorrect backend is logging
    logLines.every { it.startsWith(backend())}

    assertLogLines(logLines, firstTraceId, firstSpanId, secondTraceId, secondSpanId)
  }
}

abstract class NoInjectionSmokeTest extends LogInjectionSmokeTest {
  @Override
  def assertLogLines(List<String> logLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId) {
    assert logLines.size() == 4
    assert logLines[0].endsWith("- BEFORE FIRST SPAN")
    assert logLines[1].endsWith("- INSIDE FIRST SPAN")
    assert logLines[2].endsWith("- AFTER FIRST SPAN")
    assert logLines[3].endsWith("- INSIDE SECOND SPAN")

    return true
  }
}

abstract class RawLogInjectionSmokeTest extends LogInjectionSmokeTest {
  @Override
  def assertLogLines(List<String> logLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId) {
    assert logLines.size() == 4
    assert logLines[0].endsWith("-   - BEFORE FIRST SPAN") || logLines[0].endsWith("- 0 0 - BEFORE FIRST SPAN")
    assert logLines[1].endsWith("- ${firstTraceId} ${firstSpanId} - INSIDE FIRST SPAN")
    assert logLines[2].endsWith("-   - AFTER FIRST SPAN") || logLines[2].endsWith("- 0 0 - AFTER FIRST SPAN")
    assert logLines[3].endsWith("- ${secondTraceId} ${secondSpanId} - INSIDE SECOND SPAN")

    return true
  }
}

abstract class JULBackend extends NoInjectionSmokeTest {
  @Shared
  def propertiesFile = File.createTempFile("julConfig", ".properties")

  def backend() {
    return "JUL"
  }
  def setupSpec() {
    // JUL doesn't support reading a properties file from the classpath so everything needs
    // to be specified in a temp file
    propertiesFile.withPrintWriter {
      it.println ".level=INFO"
      it.println "handlers=java.util.logging.FileHandler"
      it.println "java.util.logging.FileHandler.pattern=${outputLogFile.absolutePath}"
      it.println "java.util.logging.FileHandler.formatter=java.util.logging.SimpleFormatter"
      it.println "java.util.logging.SimpleFormatter.format=JUL:%1\$tF %1\$tT [%4\$-7s] - %5\$s%n"
    }
  }

  def cleanupSpec() {
    propertiesFile?.delete()
  }

  List additionalArguments() {
    return ["-Djava.util.logging.config.file=${propertiesFile.absolutePath}" as String]
  }
}

class JULInterfaceJULBackend extends JULBackend {
}

class JULInterfaceLog4j2Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j2" }

  List additionalArguments() {
    return ["-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager"]
  }
}

class JCLInterfaceJULBackend extends JULBackend {
  def backend() { "JUL" }
}

class JCLInterfaceLog4j1Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j1" }
}

class JCLInterfaceLog4j2Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j2" }
}

class Log4j1InterfaceLog4j1Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j1" }
}

class Log4j1InterfaceLog4j2Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j2" }
}

class Log4j2InterfaceLog4j2Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j2" }
}

class Slf4jInterfaceLogbackBackend extends RawLogInjectionSmokeTest {
  def backend() { "Logback" }
}

class Slf4jInterfaceLog4j1Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j1" }
}

class Slf4jInterfaceLog4j2Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j2" }
}

class Slf4jInterfaceSlf4jSimpleBackend extends NoInjectionSmokeTest {
  def backend() { "Slf4jSimple" }

  List additionalArguments() {
    return ["-Dorg.slf4j.simpleLogger.logFile=${outputLogFile.absolutePath}" as String]
  }
}

class Slf4jInterfaceJULBackend extends JULBackend {
}

class Slf4jInterfaceJCLToLog4j1Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j1" }
}

class Slf4jInterfaceJCLToLog4j2Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j2" }
}

class JULInterfaceSlf4jToLogbackBackend extends RawLogInjectionSmokeTest {
  @Shared
  def propertiesFile = File.createTempFile("julConfig", ".properties")

  def backend() { "Logback" }

  def setupSpec() {
    // JUL doesn't support reading a properties file from the classpath so everything needs
    // to be specified in a temp file
    propertiesFile.withPrintWriter {
      it.println ".level=INFO"
      it.println "handlers=org.slf4j.bridge.SLF4JBridgeHandler"
    }
  }

  def cleanupSpec() {
    propertiesFile?.delete()
  }

  List additionalArguments() {
    return ["-Djava.util.logging.config.file=${propertiesFile.absolutePath}" as String]
  }
}

class JCLInterfaceSlf4jToLogbackBackend extends RawLogInjectionSmokeTest {
  def backend() { "Logback" }
}

class Log4j1InterfaceSlf4jToLogbackBackend extends RawLogInjectionSmokeTest {
  def backend() { "Logback" }
}

class Log4j2InterfaceSlf4jToLogbackBackend extends RawLogInjectionSmokeTest {
  def backend() { "Logback" }
}

class JBossInterfaceJBossBackend extends RawLogInjectionSmokeTest {
  def backend() { "JBoss" }
}

class JBossInterfaceLog4j1Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j1" }
}

class JBossInterfaceLog4j2Backend extends RawLogInjectionSmokeTest {
  def backend() { "Log4j2" }
}

class JBossInterfaceSlf4jToLogbackBackend extends RawLogInjectionSmokeTest {
  def backend() { "Logback" }
}

class JBossInterfaceJULBackend extends JULBackend {}

class FloggerInterfaceJULBackend extends JULBackend {}

class FloggerInterfaceSlf4jToLogbackBackend extends RawLogInjectionSmokeTest {
  def backend() { "Logback" }

  List additionalArguments() {
    return ["-Dflogger.backend_factory=com.google.common.flogger.backend.slf4j.Slf4jBackendFactory#getInstance"]
  }
}
