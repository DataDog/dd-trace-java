import com.mongodb.ServerAddress
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ServerId
import com.mongodb.internal.async.SingleResultCallback
import com.mongodb.internal.connection.InternalStreamConnection
import datadog.trace.agent.test.InstrumentationSpecification

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

class InternalStreamConnectionInstrumentationTest extends InstrumentationSpecification {

  def "write completion retains the parent after sendMessageAsync returns"() {
    setup:
    def streamField = InternalStreamConnection.getDeclaredField('stream')
    streamField.accessible = true
    def stream = Stub(streamField.type)
    def connection = newConnection()
    streamField.set(connection, stream)
    def completion
    stream.writeAsync(*_) >> { args -> completion = args.last() }
    def sendMessage = InternalStreamConnection.methods.find { it.name == 'sendMessageAsync' }
    def operationContext = sendMessage.parameterCount == 4 ? Stub(sendMessage.parameterTypes[2]) : null
    def callbackError
    SingleResultCallback callback = { result, error ->
      callbackError = error
      runUnderTrace('callback') {}
    }

    when:
    runUnderTrace('parent') {
      def args = sendMessage.parameterCount == 3 ? [[], 1, callback] : [[], 1, operationContext, callback]
      sendMessage.invoke(connection, args as Object[])
    }
    // Complete the write only after the submitting scope has closed, without relying on thread timing.
    assert activeSpan() == null
    assert completion != null
    if (failed) {
      completion.failed(new IOException('failed write'))
    } else {
      completion.completed(null)
    }

    then:
    (callbackError != null) == failed
    assertTraces(1) {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, 0, 'parent')
        basicSpan(it, 1, 'callback', span(0))
      }
    }

    cleanup:
    connection.close()

    where:
    failed << [false, true]
  }

  private InternalStreamConnection newConnection() {
    // The driver's constructor and Stream package changed between the supported driver versions.
    def constructor = InternalStreamConnection.constructors.min { it.parameterCount }
    def args = constructor.parameterTypes.collect { type ->
      switch (type.simpleName) {
        case 'ServerId':
          return new ServerId(new ClusterId(), new ServerAddress())
        case 'List':
          return []
        case 'CommandListener':
          return null
        case 'ClusterConnectionMode':
          return type.enumConstants.find { it.name() == 'SINGLE' }
        default:
          return Stub(type)
      }
    }
    return constructor.newInstance(args as Object[]) as InternalStreamConnection
  }
}
