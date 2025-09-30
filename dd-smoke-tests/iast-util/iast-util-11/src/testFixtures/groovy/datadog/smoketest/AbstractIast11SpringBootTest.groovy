package datadog.smoketest

import okhttp3.FormBody
import okhttp3.Request

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

abstract class AbstractIast11SpringBootTest extends AbstractIastServerSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')

    List<String> command = []
    command.add(javaPath())
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
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
    ]
  }

  void 'ssrf is present (#path)'() {
    setup:
    final url = "http://localhost:${httpPort}/ssrf/${path}"
    final body = new FormBody.Builder()
    .add(parameter, value)
    .add("async", async)
    .add("promise", promise).build()
    final request = new Request.Builder().url(url).post(body).build()

    when:
    client.newCall(request).execute()

    then:
    hasVulnerability { vul ->
      if (vul.type != 'SSRF') {
        return false
      }
      final parts = vul.evidence.valueParts
      if (parameter == 'url') {
        return parts.size() == 1
        && parts[0].value == value && parts[0].source.origin == 'http.request.parameter' && parts[0].source.name == parameter
      }

      throw new IllegalArgumentException("Parameter $parameter not supported")
    }

    where:
    path       | parameter | value                     | async   | promise
    "java-net" | "url"     | "https://dd.datad0g.com/" | "false" | "false"
    "java-net" | "url"     | "https://dd.datad0g.com/" | "true"  | "false"
    "java-net" | "url"     | "https://dd.datad0g.com/" | "true"  | "true"
  }
}
