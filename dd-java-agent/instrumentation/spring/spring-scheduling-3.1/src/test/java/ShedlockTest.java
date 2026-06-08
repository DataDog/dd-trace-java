import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.test.util.Flaky;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class ShedlockTest extends AbstractInstrumentationTest {

  @Flaky("task.invocationCount() == 0")
  @Test
  void shouldNotDisableShedlock() throws InterruptedException {
    AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(ShedlockConfig.class);
    try {
      JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
      jdbcTemplate.execute(
          "CREATE TABLE shedlock(name VARCHAR(64) NOT NULL PRIMARY KEY, lock_until TIMESTAMP NOT NULL,\n"
              + "    locked_at TIMESTAMP NOT NULL, locked_by VARCHAR(255) NOT NULL);");
      ShedLockedTask task = context.getBean(ShedLockedTask.class);

      // lock is held for more than one second: wait, then verify the task ran exactly once
      task.awaitInvocation(1000, TimeUnit.MILLISECONDS);
      assertEquals(1, task.invocationCount());
    } finally {
      context.close();
    }
  }
}
