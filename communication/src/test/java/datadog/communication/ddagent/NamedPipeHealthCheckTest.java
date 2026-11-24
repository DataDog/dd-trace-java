package datadog.communication.ddagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.PollingConditions;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The timeout and late-answer handling of the trace agent check, without a named pipe. */
class NamedPipeHealthCheckTest {

  private final ExternalAgentLauncher.NamedPipeHealthCheck healthCheck =
      new ExternalAgentLauncher.NamedPipeHealthCheck("test-pipe", true);
  private final CountDownLatch release = new CountDownLatch(1);

  @AfterEach
  void releaseBlockedRequests() {
    release.countDown();
  }

  @Test
  void countsTheResultOfARequestThatFinishesInTime() throws InterruptedException {
    assertTrue(healthCheck.agentResponds(() -> true));
    assertFalse(healthCheck.agentResponds(() -> false));
    assertFalse(
        healthCheck.agentResponds(() -> {
          throw new IOException("broken pipe");
        }));
  }

  @Test
  void countsALateAnswerAtTheNextCheck() throws InterruptedException {
    assertFalse(healthCheck.agentResponds(answersOnceReleased(true)));
    release.countDown();

    AtomicInteger newRequests = new AtomicInteger();
    new PollingConditions(5)
        .eventually(() -> assertTrue(healthCheck.agentResponds(counting(newRequests, false))));
    assertEquals(0, newRequests.get());
  }

  @Test
  void sendsANewRequestAfterALateFailure() throws InterruptedException {
    assertFalse(healthCheck.agentResponds(answersOnceReleased(false)));
    release.countDown();

    AtomicInteger newRequests = new AtomicInteger();
    new PollingConditions(5)
        .eventually(() -> assertTrue(healthCheck.agentResponds(counting(newRequests, true))));
    assertEquals(1, newRequests.get());
  }

  @Test
  @Timeout(10) // waiting on the pending request would block the supervisor thread
  void sendsNoNewRequestWhileOneIsStillWaiting() throws InterruptedException {
    assertFalse(healthCheck.agentResponds(answersOnceReleased(true)));

    AtomicInteger newRequests = new AtomicInteger();
    assertFalse(healthCheck.agentResponds(counting(newRequests, true)));
    assertEquals(0, newRequests.get());
  }

  /** A request that answers only after the test releases it, so the first check times out. */
  private Callable<Boolean> answersOnceReleased(boolean answer) {
    return () -> {
      release.await();
      return answer;
    };
  }

  private static Callable<Boolean> counting(AtomicInteger requests, boolean answer) {
    return () -> {
      requests.incrementAndGet();
      return answer;
    };
  }
}
