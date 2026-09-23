import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.DeadLetter
import akka.actor.Props
import akka.actor.UntypedActor
import akka.testkit.TestKit
import akka.testkit.TestProbe
import datadog.trace.agent.test.InstrumentationSpecification
import scala.concurrent.duration.Duration

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static java.util.concurrent.TimeUnit.SECONDS

class AkkaDeadLetterTest extends InstrumentationSpecification {
  def "release context for #count queued messages discarded on actor termination (dead letter: #deadLetter)"() {
    setup:
    def system = ActorSystem.create("dead-letter-test")
    def entered = new CountDownLatch(1)
    def stop = new CountDownLatch(1)
    def processed = new AtomicInteger()
    def actor = system.actorOf(Props.create(StoppingActor, entered, stop, processed))
    def watcher = new TestProbe(system)
    watcher.watch(actor)
    actor.tell("stop", ActorRef.noSender())
    assert entered.await(10, SECONDS)

    when:
    runUnderTrace("parent") {
      count.times {
        def message = deadLetter ? new DeadLetter("discarded", system.deadLetters(), actor) : "discarded"
        actor.tell(message, ActorRef.noSender())
      }
    }

    then:
    TEST_WRITER.empty

    when:
    stop.countDown()
    watcher.expectTerminated(actor, Duration.create(10, SECONDS))

    then:
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
    processed.get() == 0

    cleanup:
    stop?.countDown()
    TestKit.shutdownActorSystem(system, Duration.create(10, SECONDS), true)

    where:
    count | deadLetter
    1     | false
    10    | false
    1     | true
  }

  static class StoppingActor extends UntypedActor {
    private final CountDownLatch entered
    private final CountDownLatch stop
    private final AtomicInteger processed

    StoppingActor(CountDownLatch entered, CountDownLatch stop, AtomicInteger processed) {
      this.entered = entered
      this.stop = stop
      this.processed = processed
    }

    @Override
    void onReceive(Object message) {
      if (message == "stop") {
        entered.countDown()
        if (!stop.await(10, SECONDS)) {
          throw new IllegalStateException("Timed out waiting to stop the actor")
        }
        context.stop(self)
      } else {
        processed.incrementAndGet()
      }
    }
  }
}
