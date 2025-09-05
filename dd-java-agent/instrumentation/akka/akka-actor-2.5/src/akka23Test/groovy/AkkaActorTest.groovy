import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.Tags
import spock.lang.Shared

class AkkaActorTest extends InstrumentationSpecification {
  @Shared
  def akkaTester = new AkkaActors()

  def "akka actor send #name #iterations"() {
    setup:
    def barrier = akkaTester.block(name)

    when:
    (1..iterations).each {i ->
      akkaTester.send(name, "$who $i")
    }
    barrier.release()

    then:
    assertTraces(iterations) {
      (1..iterations).each {i ->
        trace(2) {
          sortSpansByStart()
          span {
            resourceName "AkkaActors.send"
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
    "tell"    | "Akka"  | "Howdy"          | 1
    "ask"     | "Makka" | "Hi-diddly-ho"   | 1
    "forward" | "Pakka" | "Hello"          | 1
    "route"   | "Rakka" | "How you doin'"  | 1
    "tell"    | "Pakka" | "Howdy"          | 10
    "ask"     | "Makka" | "Hi-diddly-ho"   | 10
    "forward" | "Akka"  | "Hello"          | 10
    "route"   | "Rakka" | "How you doin'"  | 10
  }

  def "actor message handling should close leaked scopes"() {
    when:
    akkaTester.leak("Leaker", "drip")

    then:
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        span {
          resourceName "AkkaActors.leak"
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
