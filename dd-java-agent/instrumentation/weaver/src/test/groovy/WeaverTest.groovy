import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import cats.effect.IO
import weaver.Runner

@DisableTestTrace(reason = "avoid self-tracing")
class WeaverTest extends CiVisibilityInstrumentationTest {

  def "default test"() {
    expect:
    2 + 2 == 4
  }

  //def "simple weaver test"() {
  //  given: "test runner instantiated"
  //  List<String> args = []
  //  int maxConcurrentSuites = 1
  //  def printLine = { String line -> IO(println(line)) }
  //  def runner = new Runner[IO](args, maxConcurrentSuites)(printLine)

  //  when: "we run the IO effect"
  //  runner.printLine("Runner is running inside a Spock test!")

  //  then: "no exceptions are thrown"

  //}

  @Override
  String instrumentedLibraryName() {
    return "weaver"
  }

  @Override
  String instrumentedLibraryVersion() {
    return "0.8.4"
  }
}
