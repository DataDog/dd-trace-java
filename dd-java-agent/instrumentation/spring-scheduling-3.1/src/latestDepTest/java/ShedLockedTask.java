import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShedLockedTask {

  private final CountDownLatch latch = new CountDownLatch(2);
  private final AtomicInteger invoked = new AtomicInteger();

  @Scheduled(fixedRate = 250)
  @SchedulerLock(name = "schedlockedTask", lockAtMostFor = "10000", lockAtLeastFor = "10000")
  public void schedlockedTask() {
    LockAssert.assertLocked();
    invoked.incrementAndGet();
    latch.countDown();
  }

  public int invocationCount() {
    return invoked.get();
  }

  public void awaitInvocation(long time, TimeUnit timeUnit) {
    try {
      latch.await(time, timeUnit);
    } catch (InterruptedException ignored) {
    }
  }
}
