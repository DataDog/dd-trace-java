package datadog.smoketest

import datadog.trace.agent.test.utils.PortUtils
import de.thetaphi.forbiddenapis.SuppressForbidden
import groovy.transform.CompileStatic
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeoutException

abstract class ProcessManager extends Specification {

  public static final PROFILING_START_DELAY_SECONDS = 1
  public static final int PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS = 5
  public static final String SERVICE_NAME = "smoke-test-java-app"
  public static final String ENV = "smoketest"
  public static final String VERSION = "99"

  @Shared
  protected String workingDirectory = System.getProperty("user.dir")
  @Shared
  protected String buildDirectory = System.getProperty("datadog.smoketest.builddir")
  @Shared
  protected String shadowJarPath = System.getProperty("datadog.smoketest.agent.shadowJar.path")
  @Shared
  protected boolean isIBM = System.getProperty("java.vendor", "").contains("IBM")

  @Shared
  protected static int profilingPort = -1

  @Shared
  protected String[] defaultJavaProperties

  protected int numberOfProcesses() {
    return 1
  }

  @Shared
  protected int numberOfProcesses = numberOfProcesses()

  @Shared
  protected Process[] testedProcesses = new Process[numberOfProcesses]

  // Here for backwards compatibility with single process case
  @Shared
  protected Process testedProcess

  @Shared
  private String[] logFilePaths = (0..<numberOfProcesses).collect { idx ->
    "${buildDirectory}/reports/testProcess.${this.getClass().getName()}.${idx}.log"
  }

  // Here for backwards compatibility with single process case
  @Shared
  def logFilePath = logFilePaths[0]

  def setup() {
    testedProcesses.each {
      assert it.alive: "Process $it is not availble on test beginning"
    }

    synchronized (outputThreads.testLogMessages) {
      outputThreads.testLogMessages.clear()
    }
  }

  @Shared
  @AutoCleanup
  OutputThreads outputThreads = new OutputThreads()

  class OutputThreads implements Closeable {
    final ThreadGroup tg = new ThreadGroup("smoke-output")
    final List<String> testLogMessages = new ArrayList<>()

    void close() {
      tg.interrupt()
      Thread[] threads = new Thread[tg.activeCount()]
      tg.enumerate(threads)
      threads*.join()
    }

    @CompileStatic
    class ProcessOutputRunnable implements Runnable {
      final ReadableByteChannel rc
      ByteBuffer buffer = ByteBuffer.allocate(MAX_LINE_SIZE)
      final WritableByteChannel wc
      CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()

      ProcessOutputRunnable(InputStream is, File output) {
        rc = Channels.newChannel(is)
        wc = Channels.newChannel(new FileOutputStream(output))
      }

      @Override
      void run() {
        boolean online = true
        while (online) {
          // we may have data in the buffer we did not consume for line splitting purposes
          int skip = buffer.position()

          try {
            if (rc.read(buffer) == -1) {
              online = false
            }
          } catch (IOException ioe) {
            online = false
          }

          buffer.flip()
          // write to log file
          wc.write(buffer.duplicate().position(skip) as ByteBuffer)

          // subBuff will always start at the beginning of the next (potential) line
          ByteBuffer subBuff = buffer.duplicate()
          int consumed = 0
          while (true) {
            boolean hasRemaining = subBuff.hasRemaining()
            if (hasRemaining) {
              int c = subBuff.get()
              if (c != '\n' && c != '\r') {
                continue
              }
              // found line end
            } else if (online && consumed > 0) {
              break
              // did not find line end, but we already consumed a line
              // save the data for the next read iteration
            } // else we did not consume any line, or there will be no further reads.
            // Treat the buffer as single line despite lack of terminator

            consumed += subBuff.position()
            String line = decoder.decode(subBuff.duplicate().flip() as ByteBuffer).toString().trim()
            if (line != '') {
              synchronized (testLogMessages) {
                testLogMessages << line
                testLogMessages.notifyAll()
              }
            }

            if (hasRemaining) {
              subBuff = subBuff.slice()
            } else {
              break
            }
          }

          buffer.position(consumed)
          buffer.compact()
        }
      }
    }

    void captureOutput(Process p, File outputFile) {
      new Thread(tg, new ProcessOutputRunnable(p.inputStream, outputFile)).start()
    }
  }

  def setupSpec() {
    if (buildDirectory == null || shadowJarPath == null) {
      throw new AssertionError("Expected system properties not found. Smoke tests have to be run from Gradle. Please make sure that is the case.")
    }
    assert Files.isDirectory(Paths.get(buildDirectory))
    assert Files.isRegularFile(Paths.get(shadowJarPath))

    beforeProcessBuilders()

    (0..<numberOfProcesses).each { idx ->
      ProcessBuilder processBuilder = createProcessBuilder(idx)


      processBuilder.environment().put("JAVA_HOME", System.getProperty("java.home"))
      processBuilder.environment().put("DD_API_KEY", apiKey())

      processBuilder.redirectErrorStream(true)

      Process p = processBuilder.start()
      testedProcesses[idx] = p

      outputThreads.captureOutput(p, new File(logFilePaths[idx]))
    }
    testedProcess = numberOfProcesses == 1 ? testedProcesses[0] : null


    (0..<numberOfProcesses).each { idx ->
      def curProc = testedProcesses[idx]

      if ( !curProc.isAlive() && curProc.exitValue() != 0 ) {
        def exitCode = curProc.exitValue()
        def logFile = logFilePaths[idx]

        throw new RuntimeException("Process exited abormally - exitCode:${exitCode}; logFile=${logFile}")
      }
    }
  }

  String javaPath() {
    final String separator = System.getProperty("file.separator")
    return System.getProperty("java.home") + separator + "bin" + separator + "java"
  }

  def cleanupSpec() {
    testedProcesses.each { tp ->
      int maxAttempts = 10
      Integer exitValue
      for (int attempt = 1; attempt <= maxAttempts != null; attempt++) {
        try {
          exitValue = tp?.exitValue()
          break
        }
        catch (Throwable e) {
          if (attempt == 1) {
            System.out.println("Destroying instrumented process")
            tp.destroy()
          }
          if (attempt == maxAttempts - 1) {
            System.out.println("Destroying instrumented process (forced)")
            tp.destroyForcibly()
          }
          sleep 1_000
        }
      }

      if (exitValue != null) {
        System.out.println("Instrumented process exited with " + exitValue)
      } else if (tp != null) {
        throw new TimeoutException("Instrumented process failed to exit")
      }
    }
  }

  def getProfilingUrl() {
    if (profilingPort == -1) {
      profilingPort = PortUtils.randomOpenPort()
    }
    return "http://localhost:${profilingPort}/"
  }

  /**
   * Checks if a log line is an error. This method may be overridden by test suites to consider additional messages.
   * These will be checked on suite shutdown, or explicitly by calling {@link #assertNoErrorLogs()}.
   */
  boolean isErrorLog(String line) {
    // FIXME: Flaky profiler exception. See PROF-11068.
    if (line.contains('ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception in profiling thread, trying to continue')) {
      return false
    }

    // FIXME: Flaky on Spring Boot, e.g. IastSpringBootSmokeTest :dd-smoke-tests:spring-boot-2.6-webmvc:test semeru8
    if (line.contains("I/O reactor terminated abnormally")) {
      return false
    }

    // FIXME: Flaky profiler exception. See PROF-11072.
    if (line.contains("ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception during profiling startup")) {
      return false
    }

    // FIXME: Observed in semeru8 datadog.smoketest.WildflySmokeTest
    if (line.contains("ERROR datadog.trace.agent.jmxfetch.JMXFetch - jmx collector exited with result: 0")) {
      return false
    }

    // FIXME: Spotted on multiple Spring Boot jobs, e.g. semeru11 IastSpringBootSmokeTest$WithGlobalContext
    if (line.contains("I/O reactor terminated abnormally")) {
      return false
    }

    return line.contains("ERROR") || line.contains("ASSERTION FAILED")
    || line.contains("Failed to handle exception in instrumentation")
  }

  /**
   * Asserts that there are no errors printed by the application to the log.
   * This should usually be called after the process exits, otherwise it's not guaranteed that reading the log file will
   * yield its final contents. Most tests should not need this, since it will be called at the end of every smoke test
   * suite.
   *
   * @param errorFilter Returns true if certain log line must be considered an error.
   */
  void assertNoErrorLogs(final Closure<Boolean> errorFilter = this.&isErrorLog) {
    final List<String> errorLogs = new ArrayList<>()
    forEachLogLine { String line ->
      if (errorFilter(line)) {
        errorLogs << line
      }
    }
    if (!errorLogs.isEmpty()) {
      final StringBuilder sb = new StringBuilder("Test application log contains ${errorLogs.size()} errors:\n")
      errorLogs.eachWithIndex { String entry, int i ->
        sb.append("${i + 1}: ${entry}\n")
      }
      assert errorLogs.isEmpty(), sb.toString()
    }
  }

  void forEachLogLine(Closure checker) {
    for (String lfp : logFilePaths) {
      ProcessManager.eachLine(new File(lfp)) {
        checker(it)
      }
    }
  }

  /**
   * Check if at least one log is present. It checks it since the beginning of the application, and not just during
   * the test. If the log is not present, it does not wait for it. See {@link #processTestLogLines(Closure)} for that.
   */
  boolean isLogPresent(final Closure<Boolean> checker) {
    boolean found = false
    forEachLogLine {
      if (checker(it)) {
        found = true
      }
    }
    return found
  }

  /**
   * Tries to find a log line that matches the given predicate. After reading all the
   * log lines already collected, it will wait up to 5 seconds for a new line matching
   * the predicate.
   *
   * @param checker should return true if a match is found
   */
  void processTestLogLines(Closure<Boolean> checker) {
    int l = 0
    def tlm = outputThreads.testLogMessages
    long waitStart

    while (true) {
      String msg
      synchronized (tlm) {
        if (l >= tlm.size()) {
          long waitTime
          if (waitStart != 0) {
            waitTime = 5000 - (System.currentTimeMillis() - waitStart)
            if (waitTime < 0) {
              throw new TimeoutException()
            }
          } else {
            waitStart = System.currentTimeMillis()
            waitTime = 5000
          }
          tlm.wait(waitTime)
        }
        if (l >= tlm.size()) {
          throw new TimeoutException()
        }
        // the array is only cleared at the end of the test, so index l exists
        msg = tlm.get(l++)
      }

      if (checker(msg)) {
        break
      }
    }
  }

  protected void beforeProcessBuilders() {}

  protected ProcessBuilder createProcessBuilder() {
    throw new IllegalArgumentException("Override createProcessBuilder() for single process tests")
  }

  protected ProcessBuilder createProcessBuilder(int processIndex) {
    if (processIndex > 0) {
      throw new IllegalArgumentException("Override createProcessBuilder(int processIndex) for multi process tests")
    }
    return createProcessBuilder()
  }

  String apiKey() {
    return "01234567890abcdef123456789ABCDEF"
  }

  static final int MAX_LINE_SIZE = 1024 * 1024

  @CompileStatic
  @SuppressForbidden
  private static void eachLine(File file, Closure closure) {
    def reader = new InputStreamReader(new FileInputStream(file))
    CharBuffer buffer = CharBuffer.allocate(MAX_LINE_SIZE)
    while (reader.read(buffer) != -1) {
      buffer.flip()
      while (buffer.hasRemaining()) {
        int c = buffer.get()
        if (c == '\n' || c == '\r') {
          break
        }
      }
      // we found the separator or we're out of data (max line size hit)
      // either way, report a line
      def str = buffer.duplicate().flip().toString().trim()
      if (str) {
        closure(str)
      }

      buffer.compact()
    }
    if (buffer.position() > 0) {
      buffer.flip().toString().split('\r\n|\n').each {
        closure.call(it.trim())
      }
    }
  }
}
