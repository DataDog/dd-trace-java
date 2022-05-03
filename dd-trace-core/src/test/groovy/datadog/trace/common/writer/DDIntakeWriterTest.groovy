package datadog.trace.common.writer

import datadog.trace.api.StatsDClient
import datadog.trace.common.writer.ddintake.DDIntakeApi
import datadog.trace.common.writer.ddintake.DDIntakeMapperDiscovery
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.MonitoringImpl
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

class DDIntakeWriterTest extends DDCoreSpecification{

  def api = Mock(DDIntakeApi)
  def healthMetrics = Mock(HealthMetrics)
  def monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
  def worker = Mock(TraceProcessingWorker)
  def mapperDiscovery = Mock(DDIntakeMapperDiscovery)

  @Subject
  def writer = new DDIntakeWriter(api, healthMetrics, monitoring, worker, mapperDiscovery)

  // Only used to create spans
  def dummyTracer = tracerBuilder().writer(new ListWriter()).build()

  def cleanup() {
    writer.close()
    dummyTracer.close()
  }

  def "test writer builder"() {
    when:
    def writer = DDIntakeWriter.builder().build()

    then:
    writer != null
  }

}
