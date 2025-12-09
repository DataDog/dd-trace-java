package datadog.smoketest

import static java.util.concurrent.TimeUnit.SECONDS
import okhttp3.FormBody
import okhttp3.Request
import spock.lang.Shared

import java.nio.file.Files

abstract class IastPlaySmokeTest extends AbstractIastServerSmokeTest {

  @Shared
  File playDirectory = new File("${buildDirectory}/stage/main")

  @Shared
  protected String[] defaultIastProperties =  [
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=DEFAULT",
    "-Ddd.iast.debug.enabled=true",
    "-Ddd.iast.request-sampling=100",
  ]

  @Override
  ProcessBuilder createProcessBuilder() {
    // If the server is not shut down correctly, this file can be left there and will block
    // the start of a new test
    def runningPid = new File(playDirectory.getPath(), "RUNNING_PID")
    if (runningPid.exists()) {
      runningPid.delete()
    }
    def command = isWindows() ? 'main.bat' : 'main'
    ProcessBuilder processBuilder =
      new ProcessBuilder("${playDirectory}/bin/${command}")
    processBuilder.directory(playDirectory)
    processBuilder.environment().put("JAVA_OPTS",
      (defaultIastProperties + defaultJavaProperties).collect({ it.replace(' ', '\\ ')}).join(" ")
      + " -Dconfig.file=${playDirectory}/conf/application.conf"
      + " -Dhttp.port=${httpPort}"
      + " -Dhttp.address=127.0.0.1"
      + " -Dplay.server.provider=${serverProvider()}"
      + " -Ddd.writer.type=MultiWriter:TraceStructureWriter:${output.getAbsolutePath()},DDAgentWriter")
    return processBuilder
  }

  @Override
  File createTemporaryFile() {
    new File("${buildDirectory}/tmp/trace-structure-play-2.7-iast-${serverProviderName()}.out")
  }

  abstract String serverProviderName()

  abstract String serverProvider()

  void 'Test that all the vulnerabilities are detected'() {
    given:
    def requests = []
    for (int i = 1; i <= 3; i++) {
      requests.add(new Request.Builder()
        .url("http://localhost:${httpPort}/iast/multiple_vulns/${i}?param=value${i}")
        .get()
        .build())
      requests.add(new Request.Builder()
        .url("http://localhost:${httpPort}/iast/multiple_vulns-2/${i}?param=value${i}")
        .get()
        .build())
      requests.add(new Request.Builder()
        .url("http://localhost:${httpPort}/iast/multiple_vulns/${i}")
        .post(new FormBody.Builder().add('param', "value${i}").build())
        .build())
    }


    when:
    requests.each { req ->
      client.newCall(req as Request).execute()
    }

    then: 'check has route dispatched'
    hasMeta('http.route')

    then: 'check first get mapping'
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns$1' && vul.evidence.value == 'SHA1' }
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns$1'  && vul.evidence.value == 'SHA-1' }
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns$1'  && vul.evidence.value == 'MD2'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns$1' && vul.evidence.value == 'MD5'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns$1'  && vul.evidence.value == 'RIPEMD128'}

    then: 'check first post mapping'
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$postMultipleVulns$1' && vul.evidence.value == 'SHA1' }
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$postMultipleVulns$1' && vul.evidence.value == 'SHA-1' }
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$postMultipleVulns$1' && vul.evidence.value == 'MD2'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$postMultipleVulns$1' && vul.evidence.value == 'MD5'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$postMultipleVulns$1' && vul.evidence.value == 'RIPEMD128'}

    then: 'check second get mapping'
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns2$1'  && vul.evidence.value == 'SHA1' }
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns2$1'  && vul.evidence.value == 'SHA-1' }
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns2$1'  && vul.evidence.value == 'MD2'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns2$1'  && vul.evidence.value == 'MD5'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == '$anonfun$multipleVulns2$1'  && vul.evidence.value == 'RIPEMD128'}
  }

  // Ensure to clean up server and not only the shell script that starts it
  def cleanupSpec() {
    def pid = runningServerPid()
    if (pid) {
      def commands = isWindows() ? ['taskkill', '/PID', pid, '/T', '/F'] : ['kill', '-9', pid]
      new ProcessBuilder(commands).start().waitFor(10, SECONDS)
    }
  }

  def runningServerPid() {
    def runningPid = new File(playDirectory.getPath(), 'RUNNING_PID')
    if (runningPid.exists()) {
      return Files.lines(runningPid.toPath()).findAny().orElse(null)
    }
  }

  static isWindows() {
    return System.getProperty('os.name').toLowerCase().contains("win")
  }

  static class Akka extends IastPlaySmokeTest {

    @Override
    String serverProviderName() {
      return "akka-http"
    }

    @Override
    String serverProvider() {
      return "play.core.server.AkkaHttpServerProvider"
    }
  }

  static class Netty extends IastPlaySmokeTest {
    @Override
    String serverProviderName() {
      return "netty"
    }

    @Override
    String serverProvider() {
      return "play.core.server.NettyServerProvider"
    }
  }
}
