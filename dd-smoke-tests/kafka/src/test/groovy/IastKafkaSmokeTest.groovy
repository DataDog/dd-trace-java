import datadog.smoketest.AbstractIastServerSmokeTest
import okhttp3.Request
import org.springframework.kafka.test.EmbeddedKafkaBroker
import spock.lang.Shared

import static datadog.trace.api.config.IastConfig.IAST_CONTEXT_MODE
import static datadog.trace.api.config.IastConfig.IAST_DEBUG_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_DETECTION_MODE
import static datadog.trace.api.config.IastConfig.IAST_ENABLED

class IastKafkaSmokeTest extends AbstractIastServerSmokeTest {

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

  void 'test kafka key source'() {
    setup:
    final url = "http://localhost:${httpPort}/iast/kafka?type=source_key"

    when:
    final response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.body().string() == 'OK'
    hasTainted { tainted ->
      tainted.value == 'Kafka tainted key: source_key' &&
        tainted.ranges[0].source.origin == 'kafka.message.key'
    }
  }

  void 'test kafka value source'() {
    setup:
    final url = "http://localhost:${httpPort}/iast/kafka?type=source_value"

    when:
    final response = client.newCall(new Request.Builder().url(url).get().build()).execute()

    then:
    response.body().string() == 'OK'
    hasTainted { tainted ->
      tainted.value == 'Kafka tainted value: source_value' &&
        tainted.ranges[0].source.origin == 'kafka.message.value'
    }
  }
}
