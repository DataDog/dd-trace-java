import java.util.concurrent.CountDownLatch;
import org.quartz.*;

public class QuartzTestJob implements Job {
  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    try {
      CountDownLatch latch = (CountDownLatch) context.getScheduler().getContext().get("latch");
      latch.countDown();
    } catch (SchedulerException e) {
      e.printStackTrace();
    }
  }
}
