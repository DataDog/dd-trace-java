import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.test.util.Flaky
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.jdbc.core.JdbcTemplate

import javax.sql.DataSource
import java.util.concurrent.TimeUnit

class ShedlockTest extends InstrumentationSpecification {

  @Flaky("task.invocationCount() == 0")
  def "should not disable shedlock"() {
    setup:
    def context = new AnnotationConfigApplicationContext(ShedlockConfig)
    JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource))
    jdbcTemplate.execute("CREATE TABLE shedlock(name VARCHAR(64) NOT NULL PRIMARY KEY, lock_until TIMESTAMP NOT NULL,\n" +
      "    locked_at TIMESTAMP NOT NULL, locked_by VARCHAR(255) NOT NULL);")
    def task = context.getBean(ShedLockedTask)

    expect: "lock is held for more than one second"
    !task.awaitInvocation(1000, TimeUnit.MILLISECONDS)
    task.invocationCount() == 1

    cleanup:
    context.close()
  }
}
