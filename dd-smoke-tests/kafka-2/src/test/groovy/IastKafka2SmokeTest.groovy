import datadog.smoketest.AbstractIastServerSmokeTest
import datadog.trace.agent.test.utils.OkHttpUtils
import okhttp3.Request
import org.springframework.kafka.test.EmbeddedKafkaBroker
import spock.lang.Shared

import static datadog.trace.api.config.IastConfig.IAST_CONTEXT_MODE
import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

class IastKafka2SmokeTest extends AbstractIastServerSmokeTest {

  @Shared
  EmbeddedKafkaBroker embeddedKafka

  @Override
  protected void beforeProcessBuilders() {
    embeddedKafka = new EmbeddedKafkaBroker(1, true)
    embeddedKafka.afterPropertiesSet()
  }

  @Override
  def cleanupSpec() {
    embeddedKafka.destroy()
  }

  @Override
  ProcessBuilder createProcessBuilder() {
    String springBootShadowJar = System.getProperty('datadog.smoketest.springboot.shadowJar.path')
    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_CONTEXT_MODE, 'GLOBAL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
    ])
    command.addAll((String[]) [
      '-jar',
      springBootShadowJar,
      "--server.port=${httpPort}",
      "--spring.kafka.bootstrap-servers=${embeddedKafka.getBrokersAsString()}"
    ])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    processBuilder.environment().clear()
    return processBuilder
  }

  def setupSpec() {
    // ensure everything is working fine
    final client = OkHttpUtils.client()
    final url = "http://localhost:${httpPort}/iast/health"
    for (int attempt : (0..<3)) {
      final result = client.newCall(new Request.Builder().url(url).get().build()).execute()
      if (result.body().string() == 'OK') {
        return
      }
    }
    throw new IllegalStateException('Server not properly initialized')
  }

  void 'test kafka #endpoint key source'() {
    setup:
    final type = "${endpoint}_source_key"
    final url = "http://localhost:${httpPort}/iast/kafka/$endpoint?type=${type}"

    when:
    final response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.body().string() == 'OK'
    hasTainted { tainted ->
      tainted.value == "Kafka tainted key: $type" &&
        tainted.ranges[0].source.origin == 'kafka.message.key' &&
        tainted.ranges[0].source.value == type
    }

    where:
    endpoint << ['json', 'string', 'byteArray', 'byteBuffer']
  }

  void 'test kafka #endpoint value source'() {
    setup:
    final type = "${endpoint}_source_value"
    final url = "http://localhost:${httpPort}/iast/kafka/$endpoint?type=${type}"

    when:
    final response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.body().string() == 'OK'
    hasTainted { tainted ->
      tainted.value == "Kafka tainted value: $type" &&
        tainted.ranges[0].source.origin == 'kafka.message.value' &&
        tainted.ranges[0].source.value == type
    }

    where:
    endpoint << ['json', 'string', 'byteArray', 'byteBuffer']
  }
}
