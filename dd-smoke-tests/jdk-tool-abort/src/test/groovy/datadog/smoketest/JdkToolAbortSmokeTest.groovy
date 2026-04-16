package datadog.smoketest

import datadog.trace.agent.test.server.http.TestHttpServer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

/**
 * Smoke tests that verify the agent aborts transparently when attached to a JDK tool binary.
 * <p>
 * Each test case discovers a real JDK tool binary from the test JVM's {@code $JAVA_HOME/bin/},
 * runs it with the agent attached via {@code -J-javaagent:}, and asserts that no {@code /info}
 * request reached the test backend. The {@code /info} call is the first thing a fully-initialized
 * agent makes to negotiate capabilities; its absence proves the early-abort path was taken.
 */
class JdkToolAbortSmokeTest extends Specification {

  private static final int TOOL_TIMEOUT_SECS = 15

  /**
   * Tools excluded from testing because they would not terminate on their own:
   * <ul>
   *   <li>JVM launchers ({@code java}, {@code javaw}) — they ARE the JVM, no {@code -J-} prefix
   *   <li>Long-running daemons / servers
   *   <li>GUI tools that open windows
   *   <li>Interactive REPLs
   *   <li>Deprecated packaging tools removed in JDK 14
   *   <li>Native non-JVM tools (Async Profiler shims in newer JDKs)
   * </ul>
   */
  private static final Set<String> EXCLUDED_TOOLS = [
    // JVM launchers
    "java", "javaw", "javaws",
    // Long-running daemons / servers
    "rmiregistry", "rmid", "orbd", "servertool", "tnameserv", "jstatd",
    // Requires an attached JVM
    "jsadebugd",
    // GUI tools
    "jconsole", "appletviewer", "jvisualvm", "jmc",
    // Interactive REPL
    "jshell",
    // Deprecated/removed packaging tools
    "pack200", "unpack200",
    // Native non-JVM tools (Async Profiler shims in newer JDKs)
    "asprof", "jfrconv",
    // Windows CGI adapter
    "java-rmi.cgi",
  ] as Set

  @Shared
  String agentJar = System.getProperty("datadog.smoketest.agent.shadowJar.path")

  @Shared
  List<File> jdkTools = discoverJdkTools()

  @Shared
  AtomicInteger infoRequestCount = new AtomicInteger()

  @Shared
  @AutoCleanup
  TestHttpServer server = httpServer {
    handlers {
      prefix("/info") {
        infoRequestCount.incrementAndGet()
        response.status(200).send("""{
          "version": "7.54.1",
          "endpoints": ["/v0.4/traces", "/v0.5/traces", "/telemetry/proxy/"]
        }""")
      }
      prefix("/v0.4/traces") {
        response.status(200).send()
      }
      prefix("/v0.5/traces") {
        response.status(200).send()
      }
      prefix("/telemetry/proxy/api/v2/apmtelemetry") {
        response.status(202).send()
      }
    }
  }

  def setupSpec() {
    assert agentJar != null: "datadog.smoketest.agent.shadowJar.path not set"
    assert new File(agentJar).isFile(): "Agent jar not found: $agentJar"
    assert !jdkTools.isEmpty(): "No JDK tools discovered under ${System.getProperty('java.home')}"
    server.start()
  }

  def setup() {
    infoRequestCount.set(0)
  }

  def "agent does not connect to backend when attached to JDK tool '#toolName'"() {
    when: "the tool is run with the agent attached via -J-javaagent"
    runToolWithAgent(tool)

    then: "no /info request reached the backend — agent took the early-abort path"
    infoRequestCount.get() == 0

    where:
    tool << jdkTools
    toolName = tool.name
  }

  /**
   * Runs {@code binary} with the agent attached via {@code -J-javaagent:} and the test backend
   * wired via {@code -J-Ddd.trace.agent.port=}. Closes stdin, drains stdout/stderr on a
   * background thread, and waits up to {@link #TOOL_TIMEOUT_SECS} seconds.
   */
  private void runToolWithAgent(File binary) {
    List<String> cmd = [
      binary.absolutePath,
      "-J-javaagent:${agentJar}",
      "-J-Ddd.trace.agent.port=${server.address.port}",
      "-J-Ddd.agent.host=localhost",
    ]
    Process proc = new ProcessBuilder(cmd)
      .redirectErrorStream(true)
      .start()
    // Close stdin so interactive tools (e.g. jdb) do not block waiting for input.
    proc.outputStream.close()
    // Drain stdout/stderr on a background thread to prevent pipe buffer saturation.
    Thread.start { try { proc.inputStream.bytes } catch (ignored) {} }
    if (!proc.waitFor(TOOL_TIMEOUT_SECS, TimeUnit.SECONDS)) {
      System.err.println("WARNING: ${binary.name} did not exit within ${TOOL_TIMEOUT_SECS}s — forcibly killed")
      proc.destroyForcibly()
    }
    // Brief settling delay: give any in-flight loopback TCP data time to arrive at the
    // server before the assertion in the then: block reads infoRequestCount.
    Thread.sleep(200)
  }

  /**
   * Discovers executable files under {@code $JAVA_HOME/bin/}. On JDK 8, {@code java.home}
   * points at the nested {@code jre/} directory inside the JDK, so the parent {@code bin/}
   * is also scanned to pick up SDK tools (javac, javap, …).
   * Results are sorted alphabetically and filtered against {@link #EXCLUDED_TOOLS}.
   */
  private static List<File> discoverJdkTools() {
    String javaHome = System.getProperty("java.home")
    File javaHomeDir = new File(javaHome)
    Set<File> scanDirs = new LinkedHashSet<>()
    scanDirs.add(new File(javaHomeDir, "bin"))
    // On JDK 8 java.home points to .../jdk1.8.x/jre, so scan parent bin for SDK tools
    if (javaHomeDir.name == "jre") {
      scanDirs.add(new File(javaHomeDir.parentFile, "bin"))
    }
    // Use a TreeMap so iteration order is deterministic (alphabetical by name)
    Map<String, File> tools = new TreeMap<>()
    for (File dir : scanDirs) {
      if (!dir.isDirectory()) continue
      dir.listFiles()?.each { File f ->
        if (!f.isFile() || !f.canExecute()) return
        String name = f.name.replaceFirst(/\.exe$/, "")
        if (!(name in EXCLUDED_TOOLS)) {
          tools.putIfAbsent(name, f)
        }
      }
    }
    return tools.values().toList()
  }
}
