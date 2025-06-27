package datadog.smoketest

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING
import groovy.transform.CompileDynamic
import okhttp3.FormBody
import okhttp3.Request

@CompileDynamic
class IastOverheadControlSpringBootSmokeTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
    command.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005")
    command.addAll(defaultJavaProperties)
    command.addAll(iastJvmOpts())
    command.addAll((String[]) ['-jar', springBootShadowJar, "--server.port=${httpPort}"])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    // Spring will print all environment variables to the log, which may pollute it and affect log assertions.
    processBuilder.environment().clear()
    return processBuilder
  }

  protected List<String> iastJvmOpts() {
    return [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'DEFAULT'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      withSystemProperty(IAST_REQUEST_SAMPLING, 100),
    ]
  }

  //  void 'test'() {
  //    given:
  //    // prepare a list of exactly three GET requests with path and query param
  //    def requests = []
  //    for (int i = 1; i <= 3; i++) {
  //      requests.add(new Request.Builder()
  //        .url("http://localhost:${httpPort}/multiple_vulns/${i}/?param=value${i}")
  //        .get()
  //        .build())
  //      requests.add(new Request.Builder()
  //        .url("http://localhost:${httpPort}/multiple_vulns-2/${i}/?param=value${i}")
  //        .get()
  //        .build())
  //      requests.add(new Request.Builder()
  //        .url("http://localhost:${httpPort}/multiple_vulns/${i}")
  //        .post(new FormBody.Builder().add('param', "value${i}").build())
  //        .build())
  //    }
  //
  //
  //    when:
  //    requests.each { req ->
  //      client.newCall(req as Request).execute()
  //    }
  //
  //    then: 'check first get mapping'
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulns' && vul.evidence.value == 'SHA1' }
  //    hasVulnerability { vul -> vul.type == 'NO_SAMESITE_COOKIE' && vul.location.method == 'multipleVulns'}
  //    hasVulnerability { vul -> vul.type == 'NO_HTTPONLY_COOKIE' && vul.location.method == 'multipleVulns' }
  //    hasVulnerability { vul -> vul.type == 'INSECURE_COOKIE' && vul.location.method == 'multipleVulns'}
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulns' && vul.evidence.value == 'SHA-1' }
  //    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' && vul.location.method == 'multipleVulns'}
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulns' && vul.evidence.value == 'MD2'}
  //
  //    and: 'check second get mapping'
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulns2' && vul.evidence.value == 'SHA1' }
  //    hasVulnerability { vul -> vul.type == 'NO_SAMESITE_COOKIE' && vul.location.method == 'multipleVulns2'}
  //    hasVulnerability { vul -> vul.type == 'NO_HTTPONLY_COOKIE' && vul.location.method == 'multipleVulns2' }
  //    hasVulnerability { vul -> vul.type == 'INSECURE_COOKIE' && vul.location.method == 'multipleVulns2'}
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulns2' && vul.evidence.value == 'SHA-1' }
  //    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' && vul.location.method == 'multipleVulns2'}
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulns2' && vul.evidence.value == 'MD2'}
  //
  //    and: 'check post mapping'
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulnsPost' && vul.evidence.value == 'SHA1' }
  //    hasVulnerability { vul -> vul.type == 'NO_SAMESITE_COOKIE' && vul.location.method == 'multipleVulnsPost'}
  //    hasVulnerability { vul -> vul.type == 'NO_HTTPONLY_COOKIE' && vul.location.method == 'multipleVulnsPost'}
  //    hasVulnerability { vul -> vul.type == 'INSECURE_COOKIE' && vul.location.method == 'multipleVulnsPost'}
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulnsPost' && vul.evidence.value == 'SHA-1' }
  //    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' && vul.location.method == 'multipleVulnsPost'}
  //    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'multipleVulnsPost'&& vul.evidence.value == 'MD2'}
  //  }

  void 'test different vulns in the same endpoint'() {
    given:
    // prepare a list of exactly three GET requests with path and query param
    def requests = []
    for (int i = 1; i <= 10; i++) {
      requests.add(new Request.Builder()
        .url("http://localhost:${httpPort}/different_vulns/1")
        .get()
        .build())
      requests.add(new Request.Builder()
        .url("http://localhost:${httpPort}/different_vulns/2")
        .get()
        .build())
    }

    when:
    requests.each { req ->
      client.newCall(req as Request).execute()
    }

    then:
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'differentVulns' && vul.evidence.value == 'SHA1' }
    hasVulnerability { vul -> vul.type == 'NO_SAMESITE_COOKIE' && vul.location.method == 'differentVulns'}
    hasVulnerability { vul -> vul.type == 'NO_HTTPONLY_COOKIE' && vul.location.method == 'differentVulns' }
    hasVulnerability { vul -> vul.type == 'INSECURE_COOKIE' && vul.location.method == 'differentVulns'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'differentVulns' && vul.evidence.value == 'SHA-1' }
    hasVulnerability { vul -> vul.type == 'UNTRUSTED_DESERIALIZATION' && vul.location.method == 'differentVulns'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'differentVulns' && vul.evidence.value == 'MD2'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'differentVulns' && vul.evidence.value == 'MD5'}
    hasVulnerability { vul -> vul.type == 'WEAK_HASH' && vul.location.method == 'differentVulns' && vul.evidence.value == 'RIPEMD128'}
  }

}
