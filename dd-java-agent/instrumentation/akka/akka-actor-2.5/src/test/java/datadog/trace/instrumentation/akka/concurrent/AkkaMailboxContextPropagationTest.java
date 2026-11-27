package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_ANCESTRY;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.testkit.TestKit;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import scala.concurrent.duration.Duration;

class AkkaMailboxContextPropagationTest extends AbstractInstrumentationTest {

  @Test
  void mailboxContextDoesNotLeakToSubsequentMessage() throws Exception {
    CountDownLatch firstMessageStarted = new CountDownLatch(1);
    CountDownLatch releaseFirstMessage = new CountDownLatch(1);
    CountDownLatch secondMessageFinished = new CountDownLatch(1);
    Config config =
        ConfigFactory.parseString(
                "mailbox-test-dispatcher {\n"
                    + "  executor = \"thread-pool-executor\"\n"
                    + "  thread-pool-executor {\n"
                    + "    fixed-pool-size = 1\n"
                    + "  }\n"
                    + "  throughput = 2\n"
                    + "}\n")
            .withFallback(ConfigFactory.defaultApplication());
    ActorSystem actorSystem = ActorSystem.create("mailbox-context-test", config);

    try {
      ActorRef actor =
          actorSystem.actorOf(
              Props.create(
                      MailboxContextActor.class,
                      firstMessageStarted,
                      releaseFirstMessage,
                      secondMessageFinished)
                  .withDispatcher("mailbox-test-dispatcher"));

      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        actor.tell("first", ActorRef.noSender());
      } finally {
        parent.finish();
      }

      assertTrue(firstMessageStarted.await(10, SECONDS));
      actor.tell("second", ActorRef.noSender());
      releaseFirstMessage.countDown();
      assertTrue(secondMessageFinished.await(10, SECONDS));

      assertTraces(
          trace(
              SORT_BY_ANCESTRY,
              span().operationName("parent").root(),
              span().operationName("first").childOfPrevious()),
          trace(span().operationName("second").root()));
    } finally {
      releaseFirstMessage.countDown();
      TestKit.shutdownActorSystem(actorSystem, Duration.create(10, SECONDS), true);
    }
  }

  static class MailboxContextActor extends UntypedActor {
    private final CountDownLatch firstMessageStarted;
    private final CountDownLatch releaseFirstMessage;
    private final CountDownLatch secondMessageFinished;

    MailboxContextActor(
        CountDownLatch firstMessageStarted,
        CountDownLatch releaseFirstMessage,
        CountDownLatch secondMessageFinished) {
      this.firstMessageStarted = firstMessageStarted;
      this.releaseFirstMessage = releaseFirstMessage;
      this.secondMessageFinished = secondMessageFinished;
    }

    @Override
    public void onReceive(Object message) throws Exception {
      if ("first".equals(message)) {
        handleFirstMessage();
      } else if ("second".equals(message)) {
        handleSecondMessage();
      }
    }

    private void handleFirstMessage() throws Exception {
      AgentSpan span = startSpan("test", "first");
      try (ContextScope ignored = activateSpan(span)) {
        firstMessageStarted.countDown();
        if (!releaseFirstMessage.await(10, SECONDS)) {
          throw new IllegalStateException("Timed out waiting to release first message");
        }
      } finally {
        span.finish();
      }
    }

    private void handleSecondMessage() {
      AgentSpan span = startSpan("test", "second");
      try (ContextScope ignored = activateSpan(span)) {
        secondMessageFinished.countDown();
      } finally {
        span.finish();
      }
    }
  }
}
