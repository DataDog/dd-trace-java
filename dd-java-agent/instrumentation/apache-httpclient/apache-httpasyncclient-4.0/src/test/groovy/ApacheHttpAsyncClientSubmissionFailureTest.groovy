import datadog.trace.agent.test.InstrumentationSpecification
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.nio.client.HttpAsyncClients
import org.apache.http.nio.client.methods.HttpAsyncMethods
import org.apache.http.nio.protocol.BasicAsyncResponseConsumer

import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ApacheHttpAsyncClientSubmissionFailureTest extends InstrumentationSpecification {

  def 'rejected submission releases the parent continuation: closed=#closed'() {
    setup:
    def client = HttpAsyncClients.createDefault()
    if (closed) {
      client.start()
      client.close()
    }
    def producer = HttpAsyncMethods.create(new HttpGet('http://localhost/'))
    def consumer = new BasicAsyncResponseConsumer()
    Throwable failure = null

    when:
    runUnderTrace('parent') {
      try {
        client.execute(producer, consumer, null).get(5, TimeUnit.SECONDS)
      } catch (Exception e) {
        // 4.0 throws directly; newer versions report rejection through the future.
        failure = e instanceof ExecutionException ? e.cause : e
      }
    }

    then:
    failure instanceof IllegalStateException
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    def trace = TEST_WRITER.get(0)
    trace.size() == 2
    def parent = trace.find { it.operationName.toString() == 'parent' }
    def child = trace.find { it != parent }
    parent != null
    child.parentId == parent.spanId
    child.error
    child.getTag('error.type') == failure.class.name
    child.getTag('component').toString() == 'apache-httpasyncclient'

    cleanup:
    producer.close()
    consumer.close()
    client.close()

    where:
    closed << [false, true]
  }
}
