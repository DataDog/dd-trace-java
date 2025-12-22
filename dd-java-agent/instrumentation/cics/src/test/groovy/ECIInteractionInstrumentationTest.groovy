import com.ibm.connector2.cics.CICSUserInputException
import com.ibm.connector2.cics.ECIInteractionSpec
import com.ibm.connector2.cics.ECIManagedConnectionFactory
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import javax.resource.cci.InteractionSpec
import javax.resource.spi.ConnectionManager

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.mockito.Mockito.*

/**
 * Tests for ECIInteractionInstrumentation.
 *
 * These tests purposefully don't mock all of the objects passed to execute
 * because the early failure that throws CICSUserInputException still lets us validate the instrumentation without needing to do
 * a lot more mocking (and also tying to the test more tightly to the implementation).
 */
class ECIInteractionInstrumentationTest extends InstrumentationSpecification {
  def "ECI execute creates span with minimal fields"() {
    setup:
    def spec = new ECIInteractionSpec()
    spec.setFunctionName("TESTPROG")

    def mcf = new ECIManagedConnectionFactory()
    def managedConnection = mcf.createManagedConnection(null, null)

    def mockConnectionManager = mock(ConnectionManager)
    when(mockConnectionManager.allocateConnection(any(), any())).thenReturn(managedConnection.getConnection(null, null))

    def factory = mcf.createConnectionFactory(mockConnectionManager)
    def connection = factory.getConnection()
    def interaction = connection.createInteraction()

    when:
    runUnderTrace("parent") {
      try {
        // Method will fail with CICSUserInputException (input record is null)
        interaction.execute(spec as InteractionSpec, null, null)
      } catch (CICSUserInputException ignore) {
        // Expected - we're just testing that the span is created
      } finally {
        try {
          interaction?.close()
        } catch (Throwable ignored) {}
        try {
          connection?.close()
        } catch (Throwable ignored) {}
      }
    }

    then:
    assertTraces(1) {
      trace(2) {
        span(0) {
          operationName "parent"
          parent()
        }
        span(1) {
          operationName "cics.execute"
          spanType DDSpanTypes.RPC
          resourceName "SYNC_SEND_RECEIVE TESTPROG"
          childOf(span(0))
          errored true
          tags {
            "$Tags.COMPONENT" "cics-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "rpc.system" "cics"
            "rpc.method" "TESTPROG"
            "cics.interaction" "SYNC_SEND_RECEIVE"
            "error.type" "com.ibm.connector2.cics.CICSUserInputException"
            "error.stack" String
            "error.message" String
            defaultTags()
          }
        }
      }
    }
  }

  def "ECI execute creates span with all fields"() {
    setup:
    def spec = new ECIInteractionSpec()
    spec.setFunctionName("FULLPROG")
    spec.setTranName("FULL")
    spec.setTPNName("CPMI")
    spec.setInteractionVerb(1) // SYNC_SEND_RECEIVE

    def mcf = new ECIManagedConnectionFactory()
    def managedConnection = mcf.createManagedConnection(null, null)

    def mockConnectionManager = mock(ConnectionManager)
    when(mockConnectionManager.allocateConnection(any(), any())).thenReturn(managedConnection.getConnection(null, null))

    def factory = mcf.createConnectionFactory(mockConnectionManager)
    def connection = factory.getConnection()
    def interaction = connection.createInteraction()

    when:
    runUnderTrace("parent") {
      try {
        // Method will fail with CICSUserInputException (input record is null)
        interaction.execute(spec as InteractionSpec, null, null)
      } catch (CICSUserInputException expected) {
        // Expected - we're just testing that the span is created
      } finally {
        try {
          interaction?.close()
        } catch (Throwable ignored) {}
        try {
          connection?.close()
        } catch (Throwable ignored) {}
      }
    }

    then:
    assertTraces(1) {
      trace(2) {
        span(0) {
          operationName "parent"
          parent()
        }
        span(1) {
          operationName "cics.execute"
          spanType DDSpanTypes.RPC
          resourceName "SYNC_SEND_RECEIVE FULLPROG"
          childOf(span(0))
          errored true
          tags {
            "$Tags.COMPONENT" "cics-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "rpc.system" "cics"
            "rpc.method" "FULLPROG"
            "cics.tran" "FULL"
            "cics.tpn" "CPMI"
            "cics.interaction" "SYNC_SEND_RECEIVE"
            "error.type" "com.ibm.connector2.cics.CICSUserInputException"
            "error.stack" String
            "error.message" String
            defaultTags()
          }
        }
      }
    }
  }
}
