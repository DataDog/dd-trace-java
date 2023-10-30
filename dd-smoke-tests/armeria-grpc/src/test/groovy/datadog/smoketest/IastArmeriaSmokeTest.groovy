package datadog.smoketest

import datadog.armeria.grpc.Iast
import spock.lang.Shared

import static datadog.trace.api.config.IastConfig.*

class IastArmeriaSmokeTest extends AbstractIastServerSmokeTest {

  @Shared
  IastArmeriaGrpcClient armeriaGrpcClient = new IastArmeriaGrpcClient("localhost", httpPort)

  @Override
  ProcessBuilder createProcessBuilder() {
    final jarPath = System.getProperty('datadog.smoketest.armeria.uberJar.path')
    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      "-Darmeria.http.port=${httpPort}",
      "-Dcom.linecorp.armeria.verboseResponses=true",
      '-jar',
      jarPath
    ])

    final processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  void 'test SSRF detection'() {
    setup:
    final url = 'https://dd.datad0g.com/'
    final request = Iast.Request.newBuilder().setType(Iast.Request.Type.URL)
      .setUrl(Iast.Request.Url.newBuilder().setValue(url).build()).build()

    when:
    final resp = armeriaGrpcClient.ssrf(request)

    then:
    resp != null
    hasTainted {tainted ->
      tainted.value == url &&
        tainted.ranges[0].source.name == 'root.payload_.value_' &&
        tainted.ranges[0].source.origin == 'grpc.request.body'
    }
    hasVulnerability { vul -> vul.type == 'SSRF' }
  }
}
