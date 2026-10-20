package datadog.smoketest

import java.util.concurrent.TimeUnit

abstract class AbstractOSGiSmokeTest extends AbstractSmokeTest {
  private static final long EXIT_TIMEOUT_MINUTES = 2

  @Override
  ProcessBuilder createProcessBuilder() {
    String testJar = System.getProperty("datadog.smoketest.osgi.appJar.path")
    assert new File(testJar).isFile()

    String frameworkJar = frameworkJar()
    assert new File(frameworkJar).isFile()

    String storageDir = getClass().simpleName + "-storage"
    new File(buildDirectory, storageDir).deleteDir()

    String bundlePaths = System.getProperty("datadog.smoketest.osgi.bundle.paths")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    if (isIBM) {
      // Write javacores (SIGQUIT) into the collected reports and take a system dump on SIGUSR2 for hang diagnostics.
      command.add("-Xdump:directory=${diagnosticsDirectory().absolutePath}".toString())
      command.add('-Xdump:system:events=user2,request=exclusive+prepwalk')
    }
    command.add("-Ddd.profiling.enabled=false")
    command.add((String) "-Dorg.osgi.framework.storage=${storageDir}")
    command.add("-Dorg.osgi.framework.bootdelegation=")
    command.add("-Dorg.osgi.framework.bundle.parent=framework")
    command.addAll(frameworkArguments())
    command.addAll((String[]) ["-cp", "${frameworkJar}${File.pathSeparator}${testJar}"])
    command.add('datadog.smoketest.osgi.app.OSGiApplication')
    command.add(bundlePaths)

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))

    return processBuilder
  }

  abstract String frameworkJar()

  abstract List<String> frameworkArguments()

  @Override
  boolean isErrorLog(String log) {
    super.isErrorLog(log) || log.contains("Cannot resolve type description") || log.contains("Instrumentation muzzled")
  }

  def "example application runs without errors"() {
    when:
    if (!testedProcess.waitFor(EXIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      collectDiagnostics()
    }

    then:
    testedProcess.exitValue() == 0
    processTestLogLines {
      it.contains("Transformed - instrumentation.target.class=datadog.smoketest.osgi.client.MessageClient")
    }
  }

  private File diagnosticsDirectory() {
    // Share the application log timestamp so retries keep separate dumps that match their logs.
    // Not created here: the IBM JVM creates its dump directory on the first dump, so passing runs leave no folder.
    return new File(buildDirectory, "reports/${getClass().simpleName}.${logFileTimestamp}-dumps")
  }

  /** Collects thread dumps (and a system dump on IBM) of the hanging application, then fails the test. */
  private void collectDiagnostics() {
    File directory = diagnosticsDirectory()
    AssertionError failure = new AssertionError((Object) "OSGi application did not exit within ${EXIT_TIMEOUT_MINUTES} minutes; diagnostics: ${directory}; application log: ${logFilePath}")
    try {
      // Java 8 has no public Process.pid() API.
      long pid = testedProcess.metaClass.respondsTo(testedProcess, 'pid') ? testedProcess.pid() : testedProcess.@pid
      directory.mkdirs()
      File summary = new File(directory, 'collection.log')
      summary << "Application PID: ${pid}\n"
      if (isIBM) {
        summary << 'IBM JVM: SIGQUIT thread dumps are written as javacore.*.txt and the SIGUSR2 system dump as core.*.dmp in this directory; ' +
          'threads-*.log and system-dump.log only capture the output of the kill commands\n'
      }

      List<String> threadDump
      if (isIBM) {
        threadDump = ['kill', '-3', pid.toString()]
      } else {
        File javaHome = new File(System.getProperty('java.home'))
        File jcmd = new File(javaHome.name == 'jre' ? javaHome.parentFile : javaHome, 'bin/jcmd')
        threadDump = [jcmd.path, pid.toString(), 'Thread.print', '-l']
      }
      def run = { List<String> command, String output ->
        Process diagnostic = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(new File(directory, output)).start()
        if (diagnostic.waitFor(15, TimeUnit.SECONDS)) {
          summary << "Exited ${diagnostic.exitValue()}: ${command}\n"
        } else {
          diagnostic.destroyForcibly()
          summary << "Timed out: ${command}\n"
        }
      }

      run(threadDump, 'threads-1.log')
      // Space the snapshots so a stuck thread can be told apart from a slow one.
      if (!testedProcess.waitFor(10, TimeUnit.SECONDS)) {
        run(threadDump, 'threads-2.log')
        if (isIBM) {
          run(['kill', '-USR2', pid.toString()], 'system-dump.log')
          // The VM logs the end of the dump event once the core file is in place; cleanup must not kill it earlier.
          outputThreads.processTestLogLines({ it.contains('Processed dump event "user2"') }, TimeUnit.MINUTES.toMillis(2))
          summary << 'System dump event processed\n'
        }
      }
    } catch (Exception e) {
      failure.addSuppressed(e)
    }
    throw failure
  }

  @Override
  def logLevel() {
    return "debug"
  }
}
