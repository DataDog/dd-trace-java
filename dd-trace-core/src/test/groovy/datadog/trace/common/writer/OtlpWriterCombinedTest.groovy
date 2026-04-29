package datadog.trace.common.writer

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER

import datadog.trace.core.test.DDCoreSpecification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import spock.lang.Timeout

@Timeout(10)
class OtlpWriterCombinedTest extends DDCoreSpecification {

  def "happy path over HTTP"() {
    setup:
    injectSysConfig(TRACE_OTEL_EXPORTER, "otlp")

    def received = new CountDownLatch(1)
    def server = httpServer {
      handlers {
        post("/v1/traces") {
          received.countDown()
          response.status(200).send()
        }
      }
    }
    def writer = OtlpWriter.builder()
      .endpoint(server.address.toString() + "/v1/traces")
      .flushIntervalMilliseconds(-1)
      .build()
    def tracer = tracerBuilder().writer(writer).build()

    when:
    tracer.buildSpan("fakeOperation").start().finish()
    writer.flush()

    then:
    received.await(5, TimeUnit.SECONDS)

    cleanup:
    tracer.close()
    server.close()
  }
}
