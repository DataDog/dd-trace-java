import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.concurrent.CountDownLatch;

public class QuartzTestJob implements Job {
  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    CountDownLatch latch = (CountDownLatch) context.getMergedJobDataMap().get("latch");
    latch.countDown();
  }
}
