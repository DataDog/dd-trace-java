import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.http.javadsl.model.HttpRequest
import akka.stream.ActorMaterializer
import datadog.trace.agent.test.InstrumentationSpecification
import scala.concurrent.Await
import scala.concurrent.Promise$
import scala.concurrent.duration.Duration
import scala.runtime.BoxedUnit
import spock.util.concurrent.PollingConditions

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled

class AkkaHttpTimeoutCleanupTest extends InstrumentationSpecification {

  def 'timeout cleanup does not retain a request while its entity is unfinished: propagation=#enabled'() {
    setup:
    def system = ActorSystem.create('timeout-cleanup')
    def materializer = ActorMaterializer.create(system)
    def requestEnd = Promise$.MODULE$.apply()
    def type = Class.forName('akka.http.impl.engine.server.HttpServerBluePrint$TimeoutAccessImpl')
    def constructor = type.declaredConstructors[0]
    constructor.accessible = true
    def arguments = [
      HttpRequest.create(),
      Duration.create(1, TimeUnit.DAYS),
      requestEnd.future(),
      null,
      materializer
    ]
    if (constructor.parameterCount == 6) {
      arguments.add(system.log())
    }
    def access = constructor.newInstance(arguments as Object[])
    def clear = type.getDeclaredMethod('clear')
    clear.accessible = true

    when:
    runUnderTrace('request') {
      setAsyncPropagationEnabled(enabled)
      clear.invoke(access)
      assert isAsyncPropagationEnabled() == enabled
    }

    then:
    // The request trace must finish without waiting for the entity or its timeout cleanup.
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.get(0).size() == 1
    !requestEnd.isCompleted()

    when:
    requestEnd.success(BoxedUnit.UNIT)
    def setup = Await.result(access.get(), Duration.create(5, TimeUnit.SECONDS))
    def scheduledTask = setup.class.getDeclaredMethod('scheduledTask')
    scheduledTask.accessible = true
    def task = scheduledTask.invoke(setup) as Cancellable

    then:
    new PollingConditions(timeout: 5).eventually {
      assert task.isCancelled()
    }

    cleanup:
    requestEnd?.trySuccess(BoxedUnit.UNIT)
    materializer?.shutdown()
    if (system != null) {
      Await.result(system.terminate(), Duration.create(10, TimeUnit.SECONDS))
    }

    where:
    enabled << [true, false]
  }
}
