import java.util.concurrent.CountDownLatch;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;

@DisallowConcurrentExecution
public class QuartzTestJob implements Job {
  @Override
  public void execute(JobExecutionContext context) {
    try {
      Scheduler scheduler = context.getScheduler();
      // prevent subsequent cron fires from racing with test assertions
      scheduler.standby();

      CountDownLatch latch = (CountDownLatch) scheduler.getContext().get("latch");
      latch.countDown();
    } catch (SchedulerException e) {
      e.printStackTrace();
    }
  }
}
