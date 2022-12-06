package datadog.smoketest

import datadog.trace.agent.test.utils.PortUtils
import okhttp3.Request
import org.testcontainers.containers.RabbitMQContainer
import spock.lang.Shared

import java.util.concurrent.TimeUnit

class SpringBootRabbitIntegrationTest extends AbstractServerSmokeTest {

  @Shared
  def rabbitMQContainer

  @Shared
  String rabbitHost

  @Shared
  Integer rabbitPort

  def cleanupSpec() {
    if (rabbitMQContainer) {
      rabbitMQContainer.stop()
    }
  }

  protected int numberOfProcesses() {
    return 2
  }

  @Override
  void beforeProcessBuilders() {
    rabbitMQContainer = new RabbitMQContainer("rabbitmq:3.9.20-alpine")
    rabbitMQContainer.start()
    rabbitHost = rabbitMQContainer.getHost()
    rabbitPort = rabbitMQContainer.getMappedPort(5672)
    PortUtils.waitForPortToOpen(rabbitHost, rabbitPort, 5, TimeUnit.SECONDS)
  }

  @Override
  ProcessBuilder createProcessBuilder(int processIndex) {
    String springBootShadowJar = System.getProperty("datadog.smoketest.springboot.shadowJar.path")

    List<String> command = new ArrayList<>()
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll((String[]) [
      "-Ddd.service.name=spring-rabbit-${processIndex}",
      "-Ddd.rabbit.legacy.tracing.enabled=false",
      "-Ddd.writer.type=TraceStructureWriter:${outputs[processIndex].getAbsolutePath()}:includeService:includeResource",
      "-jar",
      springBootShadowJar,
      "--server.port=${httpPorts[processIndex]}",
      "--rabbit.${processIndex == 0 ? "sender" : "receiver"}.queue=otherqueue",
    ])
    if (processIndex > 0) {
      command.add("--rabbit.receiver.forward=true")
    }
    if (rabbitHost) {
      command.add("--spring.rabbitmq.host=$rabbitHost".toString())
    }
    if (rabbitPort) {
      command.add("--spring.rabbitmq.port=$rabbitPort".toString())
    }
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
  }

  @Override
  File createTemporaryFile(int processIndex) {
    return new File("${buildDirectory}/tmp/trace-structure-rabbit.${processIndex}.out")
  }

  @Override
  protected Set<String> expectedTraces(int processIndex) {
    def service = "spring-rabbit-${processIndex}"
    Set<String> expected = [
      "[${service}:amqp.command:basic.qos]",
      "[${service}:amqp.command:basic.consume]",
      "[${service}:amqp.command:basic.ack]",
      "[${service}:amqp.command:queue.declare]"
    ]
    if (processIndex == 0) {
      expected.add("[${service}:servlet.request:GET /roundtrip/{message}[${service}:spring.handler:WebController.roundtrip[${service}:amqp.command:basic.publish <default> -> otherqueue]]]")
      expected.add("[rabbitmq:amqp.deliver:amqp.deliver queue[${service}:amqp.command:basic.deliver queue[${service}:amqp.consume:amqp.consume queue[${service}:trace.annotation:Receiver.receiveMessage]]]]")
    } else {
      expected.add("[rabbitmq:amqp.deliver:amqp.deliver otherqueue[${service}:amqp.command:basic.deliver otherqueue[${service}:amqp.consume:amqp.consume otherqueue[${service}:trace.annotation:Receiver.receiveMessage[${service}:amqp.command:basic.publish <default> -> queue]]]]]")
    }
    return expected
  }

  def "check message #message roundtrip"() {
    setup:
    String url = "http://localhost:${httpPort}/roundtrip/${message}"

    when:
    def request = new Request.Builder().url(url).get().build()
    def response = client.newCall(request).execute()

    then:
    def responseBodyStr = response.body().string()
    responseBodyStr == "Got: >${message}"
    response.code() == 200

    where:
    message << ["foo", "bar", "baz"]
  }
}
