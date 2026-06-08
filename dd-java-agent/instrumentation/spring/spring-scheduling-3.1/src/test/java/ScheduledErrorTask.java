import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;

public class ScheduledErrorTask implements Runnable {

  public static final String ERROR_MESSAGE = "scheduled boom";

  private final CountDownLatch latch = new CountDownLatch(1);

  @Scheduled(fixedRate = 5000)
  @Override
  public void run() {
    try {
      throw new IllegalStateException(ERROR_MESSAGE);
    } finally {
      latch.countDown();
    }
  }

  public boolean blockUntilExecute() throws InterruptedException {
    return latch.await(10, TimeUnit.SECONDS);
  }
}
