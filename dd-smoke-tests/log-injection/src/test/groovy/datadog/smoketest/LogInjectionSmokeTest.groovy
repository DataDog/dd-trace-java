package datadog.smoketest

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.server.http.TestHttpServer.HandlerApi.RequestApi
import datadog.trace.api.config.GeneralConfig
import datadog.trace.test.util.Flaky
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_128_BIT_TRACEID_LOGGING_ENABLED
import static datadog.trace.api.config.TracerConfig.TRACE_128_BIT_TRACEID_GENERATION_ENABLED
import static java.util.concurrent.TimeUnit.SECONDS

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
  static final int TIMEOUT_SECS = 30

  static final String LOG4J2_BACKEND = "Log4j2"

  @Shared
  File outputLogFile

  @Shared
  File outputJsonLogFile

  def jsonAdapter = new Moshi.Builder().build().adapter(Types.newParameterizedType(Map, String, Object))

  @Shared
  boolean noTags = false

  @Shared
  boolean trace128bits = true

  @Shared
  boolean appLogCollection = false

  @Shared
  @AutoCleanup
  MockBackend mockBackend = new MockBackend()

  // Captured for inclusion in failure diagnostics so we don't have to dig through Gradle reports.
  @Shared
  String launchCommand

  def setup() {
    mockBackend.reset()
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarName = getClass().simpleName
    noTags = jarName.endsWith("NoTags")
    if (noTags) {
      jarName = jarName.substring(0, jarName.length() - 6)
    }
    trace128bits = jarName.endsWith("128bTid")
    if (trace128bits) {
      jarName = jarName.substring(0, jarName.length() - 7)
    }
    appLogCollection = jarName.endsWith("AppLogCollection")
    if (appLogCollection) {
      jarName = jarName.substring(0, jarName.length() - "AppLogCollection".length())
    }
    def loggingJar = buildDirectory + "/libs/" +  jarName + ".jar"

    assert new File(loggingJar).isFile()

    outputLogFile = File.createTempFile("logTest", ".log")
    outputJsonLogFile = File.createTempFile("logTest", ".log")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    // turn off these features as their debug output can break up our expected logging lines on IBM JVMs
    // causing random test failures (we are not testing these features here so they don't need to be on)
    command.add("-Ddd.instrumentation.telemetry.enabled=false")
    command.removeAll {
      it.startsWith("-Ddd.profiling")
    }
    command.add("-Ddd.profiling.enabled=false")
    command.add("-Ddd.remote_config.enabled=true")
    command.add("-Ddd.remote_config.url=http://localhost:${server.address.port}/v0.7/config".toString())
    command.add("-Ddd.remote_config.poll_interval.seconds=0.2")
    command.add("-Ddd.trace.flush.interval=0.3")
    command.add("-Ddd.test.logfile=${outputLogFile.absolutePath}" as String)
    command.add("-Ddd.test.jsonlogfile=${outputJsonLogFile.absolutePath}" as String)
    if (noTags) {
      command.add("-Ddd.env=" as String)
      command.add("-Ddd.version=" as String)
      command.add("-Ddd.service.name=" as String)
    }
    if (!trace128bits) {
      command.add("-Ddd.$TRACE_128_BIT_TRACEID_GENERATION_ENABLED=false" as String)
      command.add("-Ddd.$TRACE_128_BIT_TRACEID_LOGGING_ENABLED=false" as String)
    }
    if (supportsDirectLogSubmission()) {
      command.add("-Ddd.$GeneralConfig.AGENTLESS_LOG_SUBMISSION_ENABLED=true" as String)
      command.add("-Ddd.$GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL=${mockBackend.intakeUrl}" as String)
    }
    if (supportsAppLogCollection()) {
      command.add("-Ddd.$GeneralConfig.APP_LOGS_COLLECTION_ENABLED=true" as String)
    }
    command.addAll(additionalArguments())
    command.addAll((String[]) ["-jar", loggingJar])

    launchCommand = command.join(" ")
    println  "COMMANDS: " + launchCommand
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  @Override
  boolean isErrorLog(String log) {
    // Exclude some errors that we consistently get because of the logging setups used here:
    if (log.contains('no applicable action for [immediateFlush]')) {
      return false
    }
    if (log.contains('JSONLayout contains an invalid element or attribute')) {
      return false
    }
    if (log.contains('JSONLayout has no parameter that matches element')) {
      return false
    }
    return super.isErrorLog(log)
  }

  @Override
  def logLevel() {
    return "debug"
  }

  @Override
  Closure decodedEvpProxyMessageCallback() {
    return {
      String path, RequestApi request ->
      try {
        boolean isCompressed = request.getHeader("Content-Encoding").contains("gzip")
        byte[] body = request.body
        if (body != null) {
          if (isCompressed) {
            ByteArrayOutputStream output = new ByteArrayOutputStream()
            byte[] buffer = new byte[4096]
            try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body))) {
              int bytesRead = input.read(buffer, 0, buffer.length)
              output.write(buffer, 0, bytesRead)
            }
            body = output.toByteArray()
          }
          final strBody = new String(body, StandardCharsets.UTF_8)
          println("evp mesg: " + strBody)
          final jsonAdapter = new Moshi.Builder().build().adapter(Types.newParameterizedType(List, Types.newParameterizedType(Map, String, Object)))
          List<Map<String, Object>> msg = jsonAdapter.fromJson(strBody)
          msg
        }
      } catch (Throwable t) {
        println("=== Failure during EvP proxy decoding ===")
        t.printStackTrace(System.out)
        throw t
      }
    }
  }

  List additionalArguments() {
    return []
  }

  def injectsRawLogs() {
    return true
  }

  def supportsJson() {
    return true
  }

  def supportsDirectLogSubmission() {
    return backend() == LOG4J2_BACKEND
  }

  def supportsAppLogCollection() {
    false
  }

  abstract backend()

  def cleanupSpec() {
    outputLogFile?.delete()
    outputJsonLogFile?.delete()
  }

  def assertRawLogLinesWithoutInjection(List<String> logLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId,
  String thirdTraceId, String thirdSpanId, String forthTraceId, String forthSpanId) {
    // Assert log line starts with backend name.
    // This avoids tests inadvertently passing because the incorrect backend is logging
    logLines.every {
      it.startsWith(backend())
    }
    assert logLines.size() == 7
    assert logLines[0].endsWith("- BEFORE FIRST SPAN")
    assert logLines[1].endsWith("- INSIDE FIRST SPAN")
    assert logLines[2].endsWith("- AFTER FIRST SPAN")
    assert logLines[3].endsWith("- INSIDE SECOND SPAN")
    assert logLines[4].endsWith("- INSIDE THIRD SPAN")
    assert logLines[5].endsWith("- INSIDE FORTH SPAN")
    assert logLines[6].endsWith("- AFTER FORTH SPAN")

    return true
  }

  def assertRawLogLinesWithInjection(List<String> logLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId,
  String thirdTraceId, String thirdSpanId, String forthTraceId, String forthSpanId) {
    // Assert log line starts with backend name.
    // This avoids tests inadvertently passing because the incorrect backend is logging
    logLines.every {
      it.startsWith(backend())
    }
    def tagsPart = noTags ? "  " : "${SERVICE_NAME} ${ENV} ${VERSION}"
    assert logLines.size() == 7
    assert logLines[0].endsWith("- ${tagsPart}   - BEFORE FIRST SPAN") || logLines[0].endsWith("- ${tagsPart} 0 0 - BEFORE FIRST SPAN")
    assert logLines[1].endsWith("- ${tagsPart} ${firstTraceId} ${firstSpanId} - INSIDE FIRST SPAN")
    assert logLines[2].endsWith("- ${tagsPart}   - AFTER FIRST SPAN") || logLines[2].endsWith("- ${tagsPart} 0 0 - AFTER FIRST SPAN")
    assert logLines[3].endsWith("- ${tagsPart} ${secondTraceId} ${secondSpanId} - INSIDE SECOND SPAN")
    assert logLines[4].endsWith("-      - INSIDE THIRD SPAN") || logLines[4].endsWith("-    0 0 - INSIDE THIRD SPAN")
    assert logLines[5].endsWith("- ${tagsPart} ${forthTraceId} ${forthSpanId} - INSIDE FORTH SPAN")
    assert logLines[6].endsWith("- ${tagsPart}   - AFTER FORTH SPAN") || logLines[6].endsWith("- ${tagsPart} 0 0 - AFTER FORTH SPAN")
    return true
  }

  def assertJsonLinesWithInjection(List<String> rawLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId,
  String thirdTraceId, String thirdSpanId, String forthTraceId, String forthSpanId) {
    def logLines = rawLines.collect {
      println it; jsonAdapter.fromJson(it) as Map
    }

    assert logLines.size() == 7

    // Log4j2's KeyValuePair for injecting static values into Json only exists in later versions of Log4j2
    // Its tested with Log4j2LatestBackend
    if (!getClass().simpleName.contains("Log4j2Backend")) {
      assert logLines.every {
        it["backend"] == backend()
      }
    }
    return assertParsedJsonLinesWithInjection(logLines, firstTraceId, firstSpanId, secondTraceId, secondSpanId, forthTraceId, forthSpanId)
  }

  private assertParsedJsonLinesWithInjection(List<Map> logLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId, String forthTraceId, String forthSpanId) {
    assert logLines.every {
      getFromContext(it, "dd.service") == noTags ? null : SERVICE_NAME
    }
    assert logLines.every {
      getFromContext(it, "dd.version") == noTags ? null : VERSION
    }
    assert logLines.every {
      getFromContext(it, "dd.env") == noTags ? null : ENV
    }

    assert getFromContext(logLines[0], "dd.trace_id") == null
    assert getFromContext(logLines[0], "dd.span_id") == null
    assert logLines[0]["message"] == "BEFORE FIRST SPAN"

    assert getFromContext(logLines[1], "dd.trace_id") == firstTraceId
    assert getFromContext(logLines[1], "dd.span_id") == firstSpanId
    assert logLines[1]["message"] == "INSIDE FIRST SPAN"

    assert getFromContext(logLines[2], "dd.trace_id") == null
    assert getFromContext(logLines[2], "dd.span_id") == null
    assert logLines[2]["message"] == "AFTER FIRST SPAN"

    assert getFromContext(logLines[3], "dd.trace_id") == secondTraceId
    assert getFromContext(logLines[3], "dd.span_id") == secondSpanId
    assert logLines[3]["message"] == "INSIDE SECOND SPAN"

    assert getFromContext(logLines[4], "dd.trace_id") == null
    assert getFromContext(logLines[4], "dd.span_id") == null
    assert logLines[4]["message"] == "INSIDE THIRD SPAN"

    assert getFromContext(logLines[5], "dd.trace_id") == forthTraceId
    assert getFromContext(logLines[5], "dd.span_id") == forthSpanId
    assert logLines[5]["message"] == "INSIDE FORTH SPAN"

    assert getFromContext(logLines[6], "dd.trace_id") == null
    assert getFromContext(logLines[6], "dd.span_id") == null
    assert logLines[6]["message"] == "AFTER FORTH SPAN"

    return true
  }

  private assertParsedJsonLinesWithAppLogCollection(List<Map> logLines, String firstTraceId, String firstSpanId, String secondTraceId, String secondSpanId, String thirdTraceId, String thirdSpanId, String forthTraceId, String forthSpanId) {
    assert logLines.every {
      getFromContext(it, "dd.service") == noTags ? null : SERVICE_NAME
    }
    assert logLines.every {
      getFromContext(it, "dd.version") == noTags ? null : VERSION
    }
    assert logLines.every {
      getFromContext(it, "dd.env") == noTags ? null : ENV
    }

    assert getFromContext(logLines[0], "dd.trace_id") == null
    assert getFromContext(logLines[0], "dd.span_id") == null
    assert logLines[0]["message"] == "BEFORE FIRST SPAN"

    assert getFromContext(logLines[1], "dd.trace_id") == firstTraceId
    assert getFromContext(logLines[1], "dd.span_id") == firstSpanId
    assert logLines[1]["message"] == "INSIDE FIRST SPAN"

    assert getFromContext(logLines[2], "dd.trace_id") == null
    assert getFromContext(logLines[2], "dd.span_id") == null
    assert logLines[2]["message"] == "AFTER FIRST SPAN"

    assert getFromContext(logLines[3], "dd.trace_id") == secondTraceId
    assert getFromContext(logLines[3], "dd.span_id") == secondSpanId
    assert logLines[3]["message"] == "INSIDE SECOND SPAN"

    assert getFromContext(logLines[4], "dd.trace_id") == thirdTraceId
    assert getFromContext(logLines[4], "dd.span_id") == thirdSpanId
    assert logLines[4]["message"] == "INSIDE THIRD SPAN"

    assert getFromContext(logLines[5], "dd.trace_id") == forthTraceId
    assert getFromContext(logLines[5], "dd.span_id") == forthSpanId
    assert logLines[5]["message"] == "INSIDE FORTH SPAN"

    assert getFromContext(logLines[6], "dd.trace_id") == null
    assert getFromContext(logLines[6], "dd.span_id") == null
    assert logLines[6]["message"] == "AFTER FORTH SPAN"

    return true
  }

  def getFromContext(Map logEvent, String key) {
    if (logEvent["contextMap"] != null) {
      return logEvent["contextMap"][key]
    }

    return logEvent[key]
  }

  /**
   * Like {@link AbstractSmokeTest#waitForTraceCount} but checks process liveness on every poll
   * iteration and dumps comprehensive diagnostic state on failure. The previous iteration of
   * this method narrowed the flake to "process alive, RC polling, captured stdout empty" but
   * could not pinpoint where the JVM was wedged — see {@link #captureFullDiagnostic} for the
   * extended state captured here, designed to discriminate between a class-load deadlock,
   * stuck @Trace advice, broken trace writer, no-op tracer, dead OutputThreads writer, and a
   * stalled stdout pipe in a single failure.
   */
  int waitForTraceCountAlive(int count) {
    try {
      defaultPoll.eventually {
        if (traceDecodingFailure != null) {
          throw traceDecodingFailure
        }
        // Check the count BEFORE liveness — the process may have exited normally
        // after delivering all traces, and we don't want to treat that as a failure.
        if (traceCount.get() >= count) {
          return
        }
        if (testedProcess != null && !testedProcess.isAlive()) {
          // RuntimeException (not AssertionError) so PollingConditions propagates
          // immediately instead of retrying for the full timeout.
          throw new RuntimeException(
          "Process exited while waiting for ${count} traces.\n" +
          captureFullDiagnostic(count))
        }
        assert traceCount.get() >= count
      }
    } catch (AssertionError e) {
      // The default error ("Condition not satisfied after 30s") is useless — enrich with diagnostic state.
      throw new AssertionError(
      "Timed out waiting for ${count} traces after ${defaultPoll.timeout}s.\n" +
      captureFullDiagnostic(count), e)
    }
    traceCount.get()
  }

  /**
   * Comprehensive diagnostic capture for the failure path. Triggers a SIGQUIT thread dump on
   * the live process, waits briefly for it to flow through the captured stdout pipe, then
   * collects everything needed to discriminate the remaining hypotheses for this flake:
   * captured stdout file size + tail (with thread dump if the OutputThreads writer is alive),
   * the application logger file (logback/log4j2/JBoss target via {@code dd.test.logfile}),
   * OutputThreads writer-thread health, and a {@code jcmd Thread.print} fallback that works
   * even if the writer thread is dead.
   */
  private String captureFullDiagnostic(int targetCount) {
    boolean alive = testedProcess != null && testedProcess.isAlive()
    long pid = -1L
    if (testedProcess != null) {
      // Process.pid() is Java 9+; the test runner JVM may be Java 8 (zulu8). Fall back gracefully.
      try {
        pid = testedProcess.pid()
      } catch (Throwable ignored) {
        // Java 8 — SIGQUIT/jcmd paths get skipped; we still capture file state + thread groups.
      }
    }

    // Trigger SIGQUIT for an in-process thread dump. The JVM writes the dump to its stderr,
    // which redirectErrorStream(true) merges into the captured stdout pipe. Sleep briefly to
    // let the writer thread drain it before we re-read the file. No-op (and harmless) on
    // Windows; in CI this runs on Linux containers.
    if (alive && pid > 0) {
      try {
        ["kill", "-3", "${pid}".toString()].execute().waitFor(2, SECONDS)
      } catch (Throwable ignored) {
        // best-effort
      }
      try {
        Thread.sleep(1500)
      } catch (InterruptedException ignored) {
        Thread.currentThread().interrupt()
      }
    }

    def sb = new StringBuilder()
    sb << "pid=${pid} alive=${alive}"
    if (testedProcess != null && !alive) {
      try {
        sb << " exitValue=${testedProcess.exitValue()}"
      } catch (Throwable ignored) {
        // exitValue may not be available
      }
    }
    sb << "\n"
    sb << "rcPolls=${rcClientMessages.size()} traceCount=${traceCount.get()} target=${targetCount}\n"
    if (rcClientDecodingFailure != null) {
      sb << "rcDecodingFailure=${rcClientDecodingFailure}\n"
    }
    if (traceDecodingFailure != null) {
      sb << "traceDecodingFailure=${traceDecodingFailure}\n"
    }
    sb << "launchCommand=${launchCommand}\n"
    sb << "outputThreads=${describeOutputThreadGroup()}\n"

    def stdoutFile = new File(logFilePath)
    if (stdoutFile.exists()) {
      sb << "capturedStdout=${stdoutFile.absolutePath} size=${stdoutFile.length()} mtime=${new Date(stdoutFile.lastModified())}\n"
      sb << "--- captured stdout (last 60 lines, post-SIGQUIT) ---\n"
      sb << tailFile(stdoutFile, 60)
      sb << "\n"
    } else {
      sb << "capturedStdout=${logFilePath} (does not exist)\n"
    }

    if (outputLogFile != null && outputLogFile.exists()) {
      sb << "appLogFile=${outputLogFile.absolutePath} size=${outputLogFile.length()}\n"
      sb << "--- app log (last 30 lines) ---\n"
      sb << tailFile(outputLogFile, 30)
      sb << "\n"
    } else if (outputLogFile != null) {
      sb << "appLogFile=${outputLogFile.absolutePath} (does not exist)\n"
    }

    if (pid > 0) {
      sb << "--- jcmd Thread.print (fallback, works even if OutputThreads writer is dead) ---\n"
      sb << jcmdThreadPrint(pid)
      sb << "\n"
    }
    return sb.toString()
  }

  private String describeOutputThreadGroup() {
    // OutputThreads creates threads in a group named "smoke-output" — see OutputThreads.java.
    ThreadGroup root = Thread.currentThread().threadGroup
    while (root.parent != null) {
      root = root.parent
    }
    Thread[] threads = new Thread[root.activeCount() + 16]
    int n = root.enumerate(threads, true)
    def details = []
    for (int i = 0; i < n; i++) {
      def t = threads[i]
      if (t == null) {
        continue
      }
      ThreadGroup tg = t.threadGroup
      if (tg != null && tg.name == "smoke-output") {
        details << "${t.name}/${t.state}/alive=${t.alive}"
      }
    }
    return "smoke-output threads=${details.size()} [${details.join(", ")}]"
  }

  // Cap on diagnostic chunks so a wedged JVM with a giant thread dump can't OOM the Gradle
  // worker or produce an unreadable test report. ~32KB is enough for typical thread dumps.
  private static final int DIAG_TAIL_BYTES = 32 * 1024

  /**
   * Returns the last {@code lines} lines (or up to {@link #DIAG_TAIL_BYTES} from the file end,
   * whichever is smaller) without loading the whole file into memory. Important on the failure
   * path because we have just appended a full JVM thread dump to the captured stdout file via
   * SIGQUIT — {@code readLines()} on that file could OOM the Gradle worker on a wedged JVM with
   * a large dump.
   */
  private String tailFile(File f, int lines) {
    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
      long len = raf.length()
      long start = Math.max(0L, len - DIAG_TAIL_BYTES)
      raf.seek(start)
      byte[] buf = new byte[(int) (len - start)]
      raf.readFully(buf)
      String chunk = new String(buf, StandardCharsets.UTF_8)
      // If we started mid-line, drop the partial first line so the tail reads cleanly.
      if (start > 0) {
        int nl = chunk.indexOf('\n')
        if (nl >= 0) {
          chunk = chunk.substring(nl + 1)
        }
      }
      String[] all = chunk.split("\\R", -1)
      int from = Math.max(0, all.length - lines)
      def out = new StringBuilder()
      if (start > 0) {
        out << "...(truncated to last ${len - start} bytes)...\n"
      }
      for (int i = from; i < all.length; i++) {
        if (i > from) {
          out << "\n"
        }
        out << all[i]
      }
      return out.toString()
    } catch (Throwable e) {
      return "(failed to read ${f.name}: ${e.message})"
    }
  }

  private String jcmdThreadPrint(long pid) {
    String jcmdPath = resolveJcmdPath()
    if (jcmdPath == null) {
      return "(jcmd not found on java.home; skipping)"
    }
    try {
      // Merge stderr into stdout and drain incrementally — for a wedged JVM the dump can be
      // larger than the OS pipe buffer (~64KB on Linux), and waiting for exit before reading
      // would deadlock both jcmd and us. See codex review.
      ProcessBuilder pb = new ProcessBuilder(jcmdPath, Long.toString(pid), "Thread.print")
      pb.redirectErrorStream(true)
      Process proc = pb.start()
      ByteArrayOutputStream baos = new ByteArrayOutputStream()
      byte[] buf = new byte[8192]
      long deadline = System.nanoTime() + SECONDS.toNanos(5)
      InputStream is = proc.getInputStream()
      while (true) {
        if (is.available() > 0) {
          int n = is.read(buf)
          if (n < 0) {
            break
          }
          // Bound the in-memory buffer so a runaway dump can't OOM us.
          if (baos.size() < DIAG_TAIL_BYTES * 2) {
            baos.write(buf, 0, n)
          }
        } else if (!proc.isAlive()) {
          // Drain any remaining bytes after exit.
          int n
          while ((n = is.read(buf)) >= 0) {
            if (baos.size() < DIAG_TAIL_BYTES * 2) {
              baos.write(buf, 0, n)
            }
          }
          break
        } else if (System.nanoTime() > deadline) {
          proc.destroyForcibly()
          proc.waitFor(1, SECONDS)
          if (baos.size() == 0) {
            return "(jcmd timed out with no output)"
          }
          break
        } else {
          Thread.sleep(50)
        }
      }
      String out = new String(baos.toByteArray(), StandardCharsets.UTF_8)
      if (out.size() > DIAG_TAIL_BYTES) {
        // Keep the head — application/agent threads tend to be earlier in the dump than
        // generic VM/GC/JIT threads, and the head is what reveals where main is wedged.
        return out.substring(0, DIAG_TAIL_BYTES) + "\n...(truncated; full dump was ${out.size()} bytes)..."
      }
      return out
    } catch (Throwable e) {
      return "(jcmd unavailable: ${e.message})"
    }
  }

  private String resolveJcmdPath() {
    // On Java 8, java.home points to the JRE subdirectory, so jcmd is at ../bin/jcmd; on
    // Java 9+ it's at bin/jcmd. Try both, fall back to PATH.
    String javaHome = System.getProperty("java.home")
    if (javaHome != null) {
      File direct = new File(javaHome, "bin/jcmd")
      if (direct.canExecute()) {
        return direct.absolutePath
      }
      File jdkSibling = new File(new File(javaHome).parentFile, "bin/jcmd")
      if (jdkSibling.canExecute()) {
        return jdkSibling.absolutePath
      }
    }
    return "jcmd"
  }

  def parseTraceFromStdOut( String line ) {
    if (line == null) {
      throw new IllegalArgumentException("Line is null")
    }
    // there's a race with stdout where lines get combined
    // this fixes that
    def lineStart = line.indexOf("TRACEID")
    def startOfNextLine = line.indexOf("[", lineStart)
    def lineEnd = startOfNextLine == -1 ? line.length() : startOfNextLine

    def unmangled = line.substring(lineStart, lineEnd)

    return unmangled.split(" ")[1..2]
  }

  @Flaky(condition = () -> JavaVirtualMachine.isIbm8() || JavaVirtualMachine.isOracleJDK8())
  def "check raw file injection"() {
    when:
    def count = waitForTraceCountAlive(2)

    def newConfig = """
        {"lib_config":
          {"log_injection_enabled":false}
        }
     """.toString()
    setRemoteConfig("datadog/2/APM_TRACING/config_overrides/config", newConfig)

    count = waitForTraceCountAlive(3)

    setRemoteConfig("datadog/2/APM_TRACING/config_overrides/config", """{"lib_config":{}}""".toString())

    // Wait for all 4 traces before waiting for process exit to ensure trace delivery is confirmed
    count = waitForTraceCountAlive(4)

    assert testedProcess.waitFor(TIMEOUT_SECS, SECONDS) : "Process did not exit within ${TIMEOUT_SECS}s"
    def exitValue = testedProcess.exitValue()

    def logLines = outputLogFile.readLines()
    println "log lines: " + logLines

    def jsonLogLines = outputJsonLogFile.readLines()
    println "json log lines: " + jsonLogLines

    def stdOutLines = new File(logFilePath).readLines()
    def (String firstTraceId, String firstSpanId) = parseTraceFromStdOut(stdOutLines.find {
      it.contains("FIRSTTRACEID")
    })
    def (String secondTraceId, String secondSpanId) = parseTraceFromStdOut(stdOutLines.find {
      it.contains("SECONDTRACEID")
    })
    def (String thirdTraceId, String thirdSpanId) = parseTraceFromStdOut(stdOutLines.find {
      it.contains("THIRDTRACEID")
    })
    def (String forthTraceId, String forthSpanId) = parseTraceFromStdOut(stdOutLines.find {
      it.contains("FORTHTRACEID")
    })

    then:
    exitValue == 0
    count == 4
    firstTraceId && firstTraceId != "0"
    checkTraceIdFormat(firstTraceId)
    firstSpanId && firstSpanId != "0"
    secondTraceId && secondTraceId != "0"
    checkTraceIdFormat(secondTraceId)
    secondSpanId && secondSpanId != "0"
    thirdTraceId && thirdTraceId != "0"
    checkTraceIdFormat(thirdTraceId)
    thirdSpanId && thirdSpanId != "0"
    forthTraceId && forthTraceId != "0"
    checkTraceIdFormat(forthTraceId)
    forthSpanId && forthSpanId != "0"

    if (injectsRawLogs()) {
      assertRawLogLinesWithInjection(logLines, firstTraceId, firstSpanId, secondTraceId, secondSpanId, thirdTraceId, thirdSpanId, forthTraceId, forthSpanId)
    } else {
      assertRawLogLinesWithoutInjection(logLines, firstTraceId, firstSpanId, secondTraceId, secondSpanId, thirdTraceId, thirdSpanId, forthTraceId, forthSpanId)
    }

    if (supportsJson()) {
      assertJsonLinesWithInjection(jsonLogLines, firstTraceId, firstSpanId, secondTraceId, secondSpanId, thirdTraceId, thirdSpanId, forthTraceId, forthSpanId)
    }

    if (supportsDirectLogSubmission()) {
      assertParsedJsonLinesWithInjection(mockBackend.waitForLogs(7), firstTraceId, firstSpanId, secondTraceId, secondSpanId, forthTraceId, forthSpanId)
    }

    if (supportsAppLogCollection()) {
      def lines = evpProxyMessages.collect {
        it.v2
      }.flatten() as List<Map<String, Object>>
      assertParsedJsonLinesWithAppLogCollection(lines, firstTraceId, firstSpanId, secondTraceId, secondSpanId, thirdTraceId, thirdSpanId, forthTraceId, forthSpanId)
    }
  }

  void checkTraceIdFormat(String traceId) {
    if (trace128bits) {
      assert traceId.matches("[0-9a-z]{32}")
    } else {
      assert traceId.matches("\\d+")
    }
  }
}

abstract class JULBackend extends LogInjectionSmokeTest {
  @Shared
  def propertiesFile = File.createTempFile("julConfig", ".properties")

  def backend() {
    "JUL"
  }

  def injectsRawLogs() {
    false
  }
  def supportsJson() {
    false
  }

  def setupSpec() {
    def isWindows = System.getProperty("os.name").toLowerCase().contains("win")
    def outputLogFilePath = outputLogFile.absolutePath
    if (isWindows) {
      // FileHandler pattern only uses / as path delimiter
      outputLogFilePath = outputLogFilePath.replace("\\", "/")
    }
    // JUL doesn't support reading a properties file from the classpath so everything needs
    // to be specified in a temp file
    propertiesFile.withPrintWriter {
      it.println ".level=INFO"
      it.println "handlers=java.util.logging.FileHandler"
      it.println "java.util.logging.FileHandler.pattern=${outputLogFilePath}"
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

class JULInterfaceLog4j2Backend extends LogInjectionSmokeTest {
  def backend() {
    LOG4J2_BACKEND
  }

  List additionalArguments() {
    return ["-Djava.util.logging.manager=org.apache.logging.log4j.jul.LogManager"]
  }
}

class JULInterfaceLog4j2BackendNoTags extends JULInterfaceLog4j2Backend {}
class JULInterfaceLog4j2Backend128bTid extends JULInterfaceLog4j2Backend {}
class JULInterfaceLog4j2LatestBackend extends JULInterfaceLog4j2Backend {}

class JULInterfaceJBossBackend extends LogInjectionSmokeTest {
  def backend() {
    "JBoss"
  }
  def supportsJson() {
    false
  }

  List additionalArguments() {
    return ["-Djava.util.logging.manager=org.jboss.logmanager.LogManager"]
  }
}

class JULInterfaceJBossBackendNoTags extends JULInterfaceJBossBackend {}
class JULInterfaceJBossBackend128bTid extends JULInterfaceJBossBackend {}
class JULInterfaceJBossLatestBackend extends JULInterfaceJBossBackend {}

class JCLInterfaceJULBackend extends JULBackend {
  def backend() {
    "JUL"
  }
}

class JCLInterfaceLog4j1Backend extends LogInjectionSmokeTest {
  def backend() {
    "Log4j1"
  }
  def supportsJson() {
    false
  }
}

class JCLInterfaceLog4j1BackendNoTags extends JCLInterfaceLog4j1Backend {}
class JCLInterfaceLog4j1LatestBackend extends JCLInterfaceLog4j1Backend {}
class JCLInterfaceLog4j1Backend128bTid extends JCLInterfaceLog4j1Backend {}

class JCLInterfaceLog4j2Backend extends LogInjectionSmokeTest {
  def backend() {
    LOG4J2_BACKEND
  }

  // workaround https://github.com/apache/logging-log4j2/issues/1865
  List additionalArguments() {
    return ['-Dorg.apache.commons.logging.LogFactory=org.apache.logging.log4j.jcl.LogFactoryImpl' as String]
  }
}

class JCLInterfaceLog4j2BackendNoTags extends JCLInterfaceLog4j2Backend {}
class JCLInterfaceLog4j2Backend128bTid extends JCLInterfaceLog4j2Backend {}
class JCLInterfaceLog4j2LatestBackend extends JCLInterfaceLog4j2Backend {}

class Log4j1InterfaceLog4j1Backend extends LogInjectionSmokeTest {
  def backend() {
    "Log4j1"
  }
  def supportsJson() {
    false
  }
}

class Log4j1InterfaceLog4j1BackendNoTags extends Log4j1InterfaceLog4j1Backend {}
class Log4j1InterfaceLog4j1Backend128bTid extends Log4j1InterfaceLog4j1Backend {}
class Log4j1InterfaceLog4j1LatestBackend extends Log4j1InterfaceLog4j1Backend {}

class Log4j1InterfaceLog4j2Backend extends LogInjectionSmokeTest {
  def backend() {
    LOG4J2_BACKEND
  }
}

class Log4j1InterfaceLog4j2BackendNoTags extends Log4j1InterfaceLog4j2Backend {}
class Log4j1InterfaceLog4j2Backend128bTid extends Log4j1InterfaceLog4j2Backend {}
class Log4j1InterfaceLog4j2LatestBackend extends Log4j1InterfaceLog4j2Backend {}

class Log4j2InterfaceLog4j2Backend extends LogInjectionSmokeTest {
  def backend() {
    LOG4J2_BACKEND
  }
}

class Log4j2InterfaceLog4j2BackendNoTags extends Log4j2InterfaceLog4j2Backend {}
class Log4j2InterfaceLog4j2Backend128bTid extends Log4j2InterfaceLog4j2Backend {}
class Log4j2InterfaceLog4j2LatestBackend extends Log4j2InterfaceLog4j2Backend {}

class Slf4jInterfaceLogbackBackend extends LogInjectionSmokeTest {
  def backend() {
    "Logback"
  }
}

class Slf4jInterfaceLogbackBackendAppLogCollection extends Slf4jInterfaceLogbackBackend {
  @Override
  def supportsDirectLogSubmission() {
    false
  }

  @Override
  def supportsAppLogCollection() {
    true
  }
}

class Slf4jInterfaceLogbackBackendNoTags extends Slf4jInterfaceLogbackBackend {}
class Slf4jInterfaceLogbackBackend128bTid extends Slf4jInterfaceLogbackBackend {}
class Slf4jInterfaceLogbackLatestBackend extends Slf4jInterfaceLogbackBackend {}

class Slf4jInterfaceLog4j1Backend extends LogInjectionSmokeTest {
  def backend() {
    "Log4j1"
  }
  def supportsJson() {
    false
  }
}

class Slf4jInterfaceLog4j1BackendNoTags extends Slf4jInterfaceLog4j1Backend {}
class Slf4jInterfaceLog4j1Backend128bTid extends Slf4jInterfaceLog4j1Backend {}
class Slf4jInterfaceLog4j1LatestBackend extends Slf4jInterfaceLog4j1Backend {}

class Slf4jInterfaceLog4j2Backend extends LogInjectionSmokeTest {
  def backend() {
    LOG4J2_BACKEND
  }
}

class Slf4jInterfaceLog4j2BackendNoTags extends Slf4jInterfaceLog4j2Backend {}
class Slf4jInterfaceLog4j2Backend128bTid extends Slf4jInterfaceLog4j2Backend {}
class Slf4jInterfaceLog4j2LatestBackend extends Slf4jInterfaceLog4j2Backend {}
class Slf4jInterfaceLog4j2BackendAppLogCollection extends Slf4jInterfaceLog4j2Backend {
  @Override
  def supportsDirectLogSubmission() {
    false
  }

  @Override
  def supportsAppLogCollection() {
    true
  }
}


class Slf4jInterfaceSlf4jSimpleBackend extends LogInjectionSmokeTest {
  def backend() {
    "Slf4jSimple"
  }
  def injectsRawLogs() {
    false
  }
  def supportsJson() {
    false
  }

  List additionalArguments() {
    return ["-Dorg.slf4j.simpleLogger.logFile=${outputLogFile.absolutePath}" as String]
  }
}

class Slf4jInterfaceJULBackend extends JULBackend {
}

class Slf4jInterfaceJCLToLog4j1Backend extends LogInjectionSmokeTest {
  def backend() {
    "Log4j1"
  }
  def supportsJson() {
    false
  }
}

class Slf4jInterfaceJCLToLog4j1BackendNoTags extends Slf4jInterfaceJCLToLog4j1Backend {}
class Slf4jInterfaceJCLToLog4j1Backend128bTid extends Slf4jInterfaceJCLToLog4j1Backend {}
class Slf4jInterfaceJCLToLog4j1LatestBackend extends Slf4jInterfaceJCLToLog4j1Backend {}

class Slf4jInterfaceJCLToLog4j2Backend extends LogInjectionSmokeTest {
  def backend() {
    LOG4J2_BACKEND
  }

  // workaround https://github.com/apache/logging-log4j2/issues/1865
  List additionalArguments() {
    return ['-Dorg.apache.commons.logging.LogFactory=org.apache.logging.log4j.jcl.LogFactoryImpl' as String]
  }
}

class Slf4jInterfaceJCLToLog4j2BackendNoTags extends Slf4jInterfaceJCLToLog4j2Backend {}
class Slf4jInterfaceJCLToLog4j2Backend128bTid extends Slf4jInterfaceJCLToLog4j2Backend {}
class Slf4jInterfaceJCLToLog4j2LatestBackend extends Slf4jInterfaceJCLToLog4j2Backend {}

class JULInterfaceSlf4jToLogbackBackend extends LogInjectionSmokeTest {
  @Shared
  def propertiesFile = File.createTempFile("julConfig", ".properties")

  def backend() {
    "Logback"
  }

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

class JULInterfaceSlf4jToLogbackBackendNoTags extends JULInterfaceSlf4jToLogbackBackend {}
class JULInterfaceSlf4jToLogbackBackend128bTid extends JULInterfaceSlf4jToLogbackBackend {}
class JULInterfaceSlf4jToLogbackLatestBackend extends JULInterfaceSlf4jToLogbackBackend {}

class JCLInterfaceSlf4jToLogbackBackend extends LogInjectionSmokeTest {
  def backend() {
    "Logback"
  }
}

class JCLInterfaceSlf4jToLogbackBackendNoTags extends JCLInterfaceSlf4jToLogbackBackend {}
class JCLInterfaceSlf4jToLogbackBackend128bTid extends JCLInterfaceSlf4jToLogbackBackend {}
class JCLInterfaceSlf4jToLogbackLatestBackend extends JCLInterfaceSlf4jToLogbackBackend {}

class Log4j1InterfaceSlf4jToLogbackBackend extends LogInjectionSmokeTest {
  def backend() {
    "Logback"
  }
}

class Log4j1InterfaceSlf4jToLogbackBackendNoTags extends Log4j1InterfaceSlf4jToLogbackBackend {}
class Log4j1InterfaceSlf4jToLogbackBackend128bTid extends Log4j1InterfaceSlf4jToLogbackBackend {}
class Log4j1InterfaceSlf4jToLogbackLatestBackend extends Log4j1InterfaceSlf4jToLogbackBackend {}

class Log4j2InterfaceSlf4jToLogbackBackend extends LogInjectionSmokeTest {
  def backend() {
    "Logback"
  }
}

class Log4j2InterfaceSlf4jToLogbackBackendNoTags extends Log4j2InterfaceSlf4jToLogbackBackend {}
class Log4j2InterfaceSlf4jToLogbackBackend128bTid extends Log4j2InterfaceSlf4jToLogbackBackend {}
class Log4j2InterfaceSlf4jToLogbackLatestBackend extends Log4j2InterfaceSlf4jToLogbackBackend {}

class JBossInterfaceJBossBackend extends LogInjectionSmokeTest {
  def backend() {
    "JBoss"
  }
  def supportsJson() {
    false
  }

  List additionalArguments() {
    return ["-Djava.util.logging.manager=org.jboss.logmanager.LogManager"]
  }
}

class JBossInterfaceJBossBackendNoTags extends JBossInterfaceJBossBackend {}
class JBossInterfaceJBossBackend128bTid extends JBossInterfaceJBossBackend {}
class JBossInterfaceJBossLatestBackend extends JBossInterfaceJBossBackend {}

class JBossInterfaceLog4j1Backend extends LogInjectionSmokeTest {
  def backend() {
    "Log4j1"
  }
  def supportsJson() {
    false
  }
}

class JBossInterfaceLog4j1BackendNoTags extends JBossInterfaceLog4j1Backend {}
class JBossInterfaceLog4j1Backend128bTid extends JBossInterfaceLog4j1Backend {}
class JBossInterfaceLog4j1LatestBackend extends JBossInterfaceLog4j1Backend {}

class JBossInterfaceLog4j2Backend extends LogInjectionSmokeTest {
  def backend() {
    LOG4J2_BACKEND
  }
}

class JBossInterfaceLog4j2BackendNoTags extends JBossInterfaceLog4j2Backend {}
class JBossInterfaceLog4j2Backend128bTid extends JBossInterfaceLog4j2Backend {}
class JBossInterfaceLog4j2LatestBackend extends JBossInterfaceLog4j2Backend {}

class JBossInterfaceSlf4jToLogbackBackend extends LogInjectionSmokeTest {
  def backend() {
    "Logback"
  }
}

class JBossInterfaceSlf4jToLogbackBackendNoTags extends JBossInterfaceSlf4jToLogbackBackend {}
class JBossInterfaceSlf4jToLogbackBackend128bTid extends JBossInterfaceSlf4jToLogbackBackend {}
class JBossInterfaceSlf4jToLogbackLatestBackend extends JBossInterfaceSlf4jToLogbackBackend {}

class JBossInterfaceJULBackend extends JULBackend {}

class FloggerInterfaceJULBackend extends JULBackend {}

class FloggerInterfaceSlf4jToLogbackBackend extends LogInjectionSmokeTest {
  def backend() {
    "Logback"
  }

  List additionalArguments() {
    return [
      "-Dflogger.backend_factory=com.google.common.flogger.backend.slf4j.Slf4jBackendFactory#getInstance"
    ]
  }
}

class FloggerInterfaceSlf4jToLogbackBackendNoTags extends FloggerInterfaceSlf4jToLogbackBackend {}
class FloggerInterfaceSlf4jToLogbackBackend128bTid extends FloggerInterfaceSlf4jToLogbackBackend {}
class FloggerInterfaceSlf4jToLogbackLatestBackend extends FloggerInterfaceSlf4jToLogbackBackend {}
