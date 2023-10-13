package datadog.smoketest

import datadog.grpc.Iast
import spock.lang.Shared

import static datadog.trace.api.config.IastConfig.*

class IastGrpcSmokeTest extends AbstractIastServerSmokeTest {

  @Shared
  IastGrpcClient grpcClient = new IastGrpcClient("localhost", httpPort)

  @Override
  ProcessBuilder createProcessBuilder() {
    final jarPath = System.getProperty('datadog.smoketest.grpc.jar.path')
    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
      "-Dgrpc.http.port=${httpPort}".toString(),
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
    final resp = grpcClient.ssrf(request)

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
