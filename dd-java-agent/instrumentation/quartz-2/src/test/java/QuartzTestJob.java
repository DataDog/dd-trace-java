import java.util.concurrent.CountDownLatch;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

public class QuartzTestJob implements Job {
  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    CountDownLatch latch = (CountDownLatch) context.getMergedJobDataMap().get("latch");
    latch.countDown();
  }
}
