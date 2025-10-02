import akka.actor.ActorSystem
import datadog.trace.agent.test.InstrumentationSpecification

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ActorInitTest extends InstrumentationSpecification {
  def "actor init doesn't block trace"() {
    when:
    runUnderTrace("parent") {
      ActorSystem.apply()
    }

    then:
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }
}
