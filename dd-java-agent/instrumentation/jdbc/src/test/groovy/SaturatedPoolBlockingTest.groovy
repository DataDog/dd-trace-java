import datadog.trace.agent.test.AgentTestRunner
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import test.TestDataSource

import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException

/**
 * Ideas taken from Hikari's com.zaxxer.hikari.pool.TestSaturatedPool830.
 */
class SaturatedPoolBlockingTest extends AgentTestRunner {
  def "saturated pool test"(int connectionTimeout, Long exhaustPoolForMillis, int expectedWaitingSpans, boolean expectedTimeout) {
    setup:
    TEST_WRITER.setFilter((trace) -> trace.get(0).getOperationName() == "test.when")

    final HikariConfig config = new HikariConfig()
    config.setPoolName("testPool")
    config.setMaximumPoolSize(1)
    config.setConnectionTimeout(connectionTimeout)
    config.setDataSourceClassName(TestDataSource.class.getName())
    final HikariDataSource ds = new HikariDataSource(config)

    when:
    if (exhaustPoolForMillis != null) {
      def saturatedConnection = ds.getConnection()
      new Thread(() -> {
        Thread.sleep(exhaustPoolForMillis)
        saturatedConnection.close()
      }, "saturated connection closer").start()
    }

    def timedOut = false
    def span = TEST_TRACER.startSpan("test", "test.when")
    try (def ignore = TEST_TRACER.activateSpan(span)) {
      def connection = ds.getConnection()
      connection.close()
    } catch (SQLTransientConnectionException e) {
      if (e.getMessage().contains("request timed out after")) {
        // Hikari, newer
        timedOut = true
      } else {
        throw e
      }
    } catch (SQLTimeoutException ignored) {
      // Hikari, older
      timedOut = true
    }
    span.finish()

    then:
    def waiting = TEST_WRITER.firstTrace().findAll {
      element -> element.getOperationName() == "pool.waiting"
    }

    print(TEST_WRITER.firstTrace())

    verifyAll {
      TEST_WRITER.size() == 1
      waiting.size() == expectedWaitingSpans
      timedOut == expectedTimeout
    }

    where:
    connectionTimeout | exhaustPoolForMillis | expectedWaitingSpans | expectedTimeout
    1000              | null                 | 0                    | false
    1000              | null                 | 0                    | false
    1000              | 500                  | 1                    | false
    1000              | 1500                 | 1                    | true
  }
}
