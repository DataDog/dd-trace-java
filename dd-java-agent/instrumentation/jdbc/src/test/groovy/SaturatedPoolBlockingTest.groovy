import datadog.trace.agent.test.AgentTestRunner
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.dbcp2.BasicDataSource
import org.apache.commons.pool2.BaseObject
import org.apache.commons.pool2.PooledObject
import org.apache.commons.pool2.PooledObjectFactory
import org.apache.commons.pool2.impl.DefaultPooledObject
import org.apache.commons.pool2.impl.GenericObjectPool
import test.TestDataSource
import test.TestDriver

import javax.sql.DataSource
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException
import java.time.Duration

/**
 * Ideas taken from Hikari's com.zaxxer.hikari.pool.TestSaturatedPool830.
 */
class SaturatedPoolBlockingTest extends AgentTestRunner {
  public static final int CONNECTION_TIMEOUT = 1000

  def "saturated pool test"(Closure<DataSource> createDataSource, Long exhaustPoolForMillis, int expectedWaitingSpans, boolean expectedTimeout) {
    setup:
    TEST_WRITER.setFilter((trace) -> trace.get(0).getOperationName() == "test.when")
    final DataSource ds = createDataSource()

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
    } catch (SQLException e) {
      if (e.getMessage().contains("pool error Timeout waiting for idle object")) {
        // dbcp2
        timedOut = true
      } else {
        throw e
      }
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
    createDataSource       | exhaustPoolForMillis | expectedWaitingSpans | expectedTimeout
    this.&hikariDataSource | null                 | 0                    | false
    this.&hikariDataSource | null                 | 0                    | false
    this.&hikariDataSource | 500                  | 1                    | false
    this.&hikariDataSource | 1500                 | 1                    | true
    this.&dbcp2DataSource  | null                 | 0                    | false
    this.&dbcp2DataSource  | null                 | 0                    | false
    this.&dbcp2DataSource  | 500                  | 1                    | false
    this.&dbcp2DataSource  | 1500                 | 1                    | true
  }

  // Make sure we don't create spans for pollFirst calls to LinkedBlockingDeque unless they are made by dbcp2
  def "non-dbcp2 LinkedBlockingDeque"() {
    setup:
    def pool = new GenericObjectPool<>(new PooledObjectFactory() {

      @Override
      void activateObject(PooledObject p) throws Exception {
      }

      @Override
      void destroyObject(PooledObject p) throws Exception {
      }

      @Override
      PooledObject makeObject() throws Exception {
        return new DefaultPooledObject(new Object())
      }

      @Override
      void passivateObject(PooledObject p) throws Exception {
      }

      @Override
      boolean validateObject(PooledObject p) {
        return false
      }
    })
    pool.setMaxTotal(1)

    when:
    def exhaustPoolForMillis = 500
    def saturatedConnection = pool.borrowObject()
    new Thread(() -> {
      Thread.sleep(exhaustPoolForMillis)
      pool.returnObject(saturatedConnection)
    }, "saturated connection closer").start()

    pool.borrowObject(1000)

    then:
    TEST_WRITER.size() == 0
  }

  private static DataSource hikariDataSource() {
    final HikariConfig config = new HikariConfig()
    config.setPoolName("testPool")
    config.setMaximumPoolSize(1)
    config.setConnectionTimeout(CONNECTION_TIMEOUT)
    config.setDataSourceClassName(TestDataSource.class.getName())
    return new HikariDataSource(config)
  }

  private static DataSource dbcp2DataSource() {
    final BasicDataSource ds = new BasicDataSource()
    ds.setMaxTotal(1)
    ds.setMaxWait(Duration.ofMillis(CONNECTION_TIMEOUT))
    ds.setDriverClassName(TestDriver.class.getName())
    ds.start()
    return ds
  }
}
