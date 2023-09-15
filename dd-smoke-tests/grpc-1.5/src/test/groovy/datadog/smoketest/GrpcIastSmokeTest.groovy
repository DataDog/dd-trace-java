package datadog.smoketest

import datadog.smoketest.grpc.IastGrpc
import datadog.smoketest.grpc.IastOuterClass
import io.grpc.Grpc
import io.grpc.InsecureChannelCredentials
import io.grpc.ManagedChannel

import java.util.concurrent.TimeUnit

import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

class GrpcIastSmokeTest extends AbstractIastServerSmokeTest {

  private ManagedChannel channel

  def setup() {
    channel = Grpc.newChannelBuilder("localhost:${httpPort}", InsecureChannelCredentials.create()).build()
  }

  def cleanup() {
    channel?.shutdownNow()?.awaitTermination(15, TimeUnit.SECONDS)
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    final jarPath = System.getProperty('datadog.smoketest.grpc.jar.path')
    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll([
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
    final client = IastGrpc.newBlockingStub(channel)
    final url = 'https://dd.datad0g.com/'

    when:
    final resp = client.serverSideRequestForgery(IastOuterClass.UrlRequest.newBuilder().setUrl(url).build())

    then:
    resp != null
    hasVulnerability { vul -> vul.type == 'SSRF' }
  }
}
