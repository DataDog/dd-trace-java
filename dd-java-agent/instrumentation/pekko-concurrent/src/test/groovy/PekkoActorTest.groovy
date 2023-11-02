import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

class PekkoActorTest extends AgentTestRunner {
  @Shared
  def pekkoTester = new PekkoActors()

  def "pekko actor send #name #iterations"() {
    setup:
    def barrier = pekkoTester.block(name)

    when:
    (1..iterations).each {i ->
      pekkoTester.send(name, "$who $i")
    }
    barrier.release()

    then:
    assertTraces(iterations) {
      (1..iterations).each {i ->
        trace(2) {
          sortSpansByStart()
          span {
            resourceName "PekkoActors.send"
            operationName "$name"
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
          span {
            resourceName "Receiver.tracedChild"
            operationName "$expectedGreeting, $who $i"
            childOf(span(0))
            tags {
              "$Tags.COMPONENT" "trace"
              defaultTags()
            }
          }
        }
      }
    }

    where:
    name      | who     | expectedGreeting | iterations
    "tell"    | "Ekko"  | "Howdy"          | 1
    "ask"     | "Mekko" | "Hi-diddly-ho"   | 1
    "forward" | "Pekko" | "Hello"          | 1
    "route"   | "Rekko" | "How you doin'"  | 1
    "tell"    | "Pekko" | "Howdy"          | 10
    "ask"     | "Mekko" | "Hi-diddly-ho"   | 10
    "forward" | "Ekko"  | "Hello"          | 10
    "route"   | "Rekko" | "How you doin'"  | 10
  }

  def "actor message handling should close leaked scopes"() {
    when:
    pekkoTester.leak("Leaker", "drip")

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span {
          resourceName "PekkoActors.leak"
          operationName "leak all the things"
          tags {
            "$Tags.COMPONENT" "trace"
            defaultTags()
          }
        }
        span {
          resourceName("drip")
          operationName "Howdy, Leaker"
          childOf(span(0))
          tags {
            defaultTags()
          }
        }
      }
    }
  }
}
