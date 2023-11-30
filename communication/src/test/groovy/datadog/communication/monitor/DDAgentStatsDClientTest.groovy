package datadog.communication.monitor


import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.GeneralConfig.DOGSTATSD_START_DELAY
import static datadog.communication.monitor.DDAgentStatsDClientManager.statsDClientManager

class DDAgentStatsDClientTest extends DDSpecification {

  def "single statsd client"() {
    setup:
    injectSysConfig(DOGSTATSD_START_DELAY, '0')
    def server = new StatsDServer()
    server.start()

    def client = statsDClientManager().statsDClient('127.0.0.1', server.socket.localPort, null, namespace, constantTags as String[], false)

    String metricName = "test.metric"
    String checkName = "test.check"
    String[] tags = ["type:BufferPool", "jmx_domain:java.nio"]

    expect:

    client.incrementCounter(metricName, tags)
    server.waitForMessage().startsWith("$expectedMetricName:1|c|#$expectedTags")

    client.count(metricName, Integer.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-2147483648|c|#$expectedTags")
    client.count(metricName, Integer.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:2147483647|c|#$expectedTags")
    client.count(metricName, Long.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-9223372036854775808|c|#$expectedTags")
    client.count(metricName, Long.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:9223372036854775807|c|#$expectedTags")

    client.gauge(metricName, Integer.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-2147483648|g|#$expectedTags")
    client.gauge(metricName, Integer.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:2147483647|g|#$expectedTags")
    client.gauge(metricName, Long.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-9223372036854775808|g|#$expectedTags")
    client.gauge(metricName, Long.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:9223372036854775807|g|#$expectedTags")

    client.gauge(metricName, -Math.E, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-2.718282|g|#$expectedTags")
    client.gauge(metricName, Math.PI, tags)
    server.waitForMessage().startsWith("$expectedMetricName:3.141593|g|#$expectedTags")

    client.histogram(metricName, Integer.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-2147483648|h|#$expectedTags")
    client.histogram(metricName, Integer.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:2147483647|h|#$expectedTags")
    client.histogram(metricName, Long.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-9223372036854775808|h|#$expectedTags")
    client.histogram(metricName, Long.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:9223372036854775807|h|#$expectedTags")

    client.histogram(metricName, -Math.E, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-2.718282|h|#$expectedTags")
    client.histogram(metricName, Math.PI, tags)
    server.waitForMessage().startsWith("$expectedMetricName:3.141593|h|#$expectedTags")

    client.distribution(metricName, Integer.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-2147483648|d|#$expectedTags")
    client.distribution(metricName, Integer.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:2147483647|d|#$expectedTags")
    client.distribution(metricName, Long.MIN_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-9223372036854775808|d|#$expectedTags")
    client.distribution(metricName, Long.MAX_VALUE, tags)
    server.waitForMessage().startsWith("$expectedMetricName:9223372036854775807|d|#$expectedTags")

    client.distribution(metricName, -Math.E, tags)
    server.waitForMessage().startsWith("$expectedMetricName:-2.718282|d|#$expectedTags")
    client.distribution(metricName, Math.PI, tags)
    server.waitForMessage().startsWith("$expectedMetricName:3.141593|d|#$expectedTags")

    client.serviceCheck(checkName, "OK", null, tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|0|#$expectedTags")
    client.serviceCheck(checkName, "WARN", null, tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|1|#$expectedTags")
    client.serviceCheck(checkName, "WARNING", null, tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|1|#$expectedTags")
    client.serviceCheck(checkName, "CRITICAL", null, tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|2|#$expectedTags")
    client.serviceCheck(checkName, "ERROR", null, tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|2|#$expectedTags")
    client.serviceCheck(checkName, "_UNKNOWN_", null, tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|3|#$expectedTags")

    client.serviceCheck(checkName, "OK", "testing", tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|0|#$expectedTags|m:testing")
    client.serviceCheck(checkName, "WARN", "testing", tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|1|#$expectedTags|m:testing")
    client.serviceCheck(checkName, "WARNING", "testing", tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|1|#$expectedTags|m:testing")
    client.serviceCheck(checkName, "CRITICAL", "testing", tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|2|#$expectedTags|m:testing")
    client.serviceCheck(checkName, "ERROR", "testing", tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|2|#$expectedTags|m:testing")
    client.serviceCheck(checkName, "_UNKNOWN_", "testing", tags)
    server.waitForMessage().startsWith("_sc|$expectedCheckName|3|#$expectedTags|m:testing")

    cleanup:
    client.close()
    server.close()

    where:
    // spotless:off
    namespace | constantTags                        | expectedMetricName    | expectedCheckName    | expectedTags
    null      | null                                | "test.metric"         | "test.check"         | "jmx_domain:java.nio,type:BufferPool"
    null      | ["lang:java", "lang_version:1.8.0"] | "test.metric"         | "test.check"         | "jmx_domain:java.nio,type:BufferPool,lang:java,lang_version:1.8.0"
    "example" | null                                | "example.test.metric" | "example.test.check" | "jmx_domain:java.nio,type:BufferPool"
    "example" | ["lang:java", "lang_version:1.8.0"] | "example.test.metric" | "example.test.check" | "jmx_domain:java.nio,type:BufferPool,lang:java,lang_version:1.8.0"
    // spotless:on
  }

  def "multiple statsd clients"() {
    setup:
    injectSysConfig(DOGSTATSD_START_DELAY, '0')
    def server = new StatsDServer()
    server.start()

    def client1 = statsDClientManager().statsDClient('127.0.0.1', server.socket.localPort, null, null, null, false)
    def client2 = statsDClientManager().statsDClient('127.0.0.1', server.socket.localPort, null, "example", null, false)
    def client3 = statsDClientManager().statsDClient('127.0.0.1', server.socket.localPort, null, null, ["lang:java", "lang_version:1.8.0"] as String[], false)
    def client4 = statsDClientManager().statsDClient('127.0.0.1', server.socket.localPort, null, "example", ["lang:java", "lang_version:1.8.0"] as String[], false)

    String metricName = "test.metric"
    String[] metricTags = ["type:BufferPool", "jmx_domain:java.nio"]

    expect:
    client1.incrementCounter(metricName, metricTags)
    server.waitForMessage().startsWith("test.metric:1|c|#jmx_domain:java.nio,type:BufferPool")
    client1.close()

    client2.incrementCounter(metricName, metricTags)
    server.waitForMessage().startsWith("example.test.metric:1|c|#jmx_domain:java.nio,type:BufferPool")
    client2.close()

    client3.incrementCounter(metricName, metricTags)
    server.waitForMessage().startsWith("test.metric:1|c|#jmx_domain:java.nio,type:BufferPool,lang:java,lang_version:1.8.0")
    client3.close()

    client4.incrementCounter(metricName, metricTags)
    server.waitForMessage().startsWith("example.test.metric:1|c|#jmx_domain:java.nio,type:BufferPool,lang:java,lang_version:1.8.0")
    client4.close()

    cleanup:
    server.close()
  }


  private static class StatsDServer extends Thread {
    private final DatagramSocket socket
    private volatile String lastMessage

    StatsDServer() {
      socket = new DatagramSocket()
    }

    void run() {
      byte[] buf = new byte[1024]
      DatagramPacket packet = new DatagramPacket(buf, buf.length)
      while ('stop' != lastMessage) {
        socket.receive(packet)
        lastMessage = new String(packet.getData(), 0, packet.getLength())
      }
      socket.close()
    }

    String lastMessage() {
      return lastMessage
    }

    String waitForMessage() {
      while (null == lastMessage) {
        Thread.yield()
      }
      try {
        return lastMessage.trim()
      } finally {
        lastMessage = null
      }
    }

    void close() {
      lastMessage = 'stop'
      interrupt()
    }
  }
}
