import com.zaxxer.hikari.pool.ProxyLeakTask
import datadog.environment.JavaVirtualMachine
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling
import jdk.jfr.Recording
import org.openjdk.jmc.common.item.Attribute
import org.openjdk.jmc.common.item.IAttribute
import org.openjdk.jmc.common.item.ItemFilters
import org.openjdk.jmc.common.unit.UnitLookup
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit
import spock.lang.Requires
import spock.lang.Shared

import java.nio.file.Files

@Requires({
  !JavaVirtualMachine.isJ9()
})
class KnownExcludesForkedTest extends InstrumentationSpecification {
  private static final IAttribute<String> TYPE =
  Attribute.attr("type", "type", "Exception type", UnitLookup.PLAIN_TEXT)

  @Shared
  Recording recording

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig("profiling.enabled", "true")
    injectSysConfig("profiling.start-force-first", "true")
  }

  def setupSpec() {
    recording = new Recording()
    recording.enable("datadog.ExceptionCount")
    recording.start()
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling()
  }

  def "obey excluded"() {
    when:
    println("Generating exceptions ...")
    for (int i = 0; i < 50; i++) {
      new ProxyLeakTask()
      new NullPointerException()
    }
    println("Exceptions generated")

    def tempPath = Files.createTempDirectory("test-recording")
    def recFile = tempPath.resolve(KnownExcludesForkedTest.name.replace('.', '_') + ".jfr")
    recFile.toFile().deleteOnExit()
    recording.dump(recFile)
    recording.stop()

    def events = JfrLoaderToolkit.loadEvents(recFile.toFile()).apply(ItemFilters.type("datadog.ExceptionCount"))

    then:
    events.apply(ItemFilters.equals(TYPE, NullPointerException.canonicalName)).hasItems()
    !events.apply(ItemFilters.equals(TYPE, ProxyLeakTask.canonicalName)).hasItems()
  }
}
