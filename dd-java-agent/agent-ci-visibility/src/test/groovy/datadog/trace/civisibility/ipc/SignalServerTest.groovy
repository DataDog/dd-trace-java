package datadog.trace.civisibility.ipc

import datadog.trace.api.DDTraceId
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicBoolean

class SignalServerTest extends Specification {

  def signalClientTimeoutMillis = 10_000

  def "test message send and receive"() {
    given:
    def signalProcessed = new AtomicBoolean(false)
    def signal = new ModuleExecutionResult(DDTraceId.from(123), 456, true, true, false, false, false, true, 1, Collections.singletonList(new TestFramework("junit", "4.13.2")))
    def server = new SignalServer()
    def received = new ArrayList()

    expect:
    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      received.add(it)
      signalProcessed.set(true)
      return AckResponse.INSTANCE
    })
    server.start()

    def address = server.getAddress()
    try (def client = new SignalClient(address, signalClientTimeoutMillis)) {
      client.send(signal)
      // verify that the signal was processed by the server by the time send() method returns
      // (we want the send() method to be truly synchronous in that regard)
      signalProcessed.get()
    }

    received.size() == 1
    received[0] == signal

    cleanup:
    server.stop()
  }

  def "test multiple messages send and receive"() {
    given:
    def signalA = new ModuleExecutionResult(DDTraceId.from(123), 456, false, false, false, false, false, false, 0, Collections.singletonList(new TestFramework("junit", "4.13.2")))
    def signalB = new ModuleExecutionResult(DDTraceId.from(234), 567, true, true, false, false, true, false, 1, Collections.singletonList(new TestFramework("junit", "4.13.2")))
    def server = new SignalServer()
    def received = new ArrayList()

    when:
    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      received.add(it)
      return AckResponse.INSTANCE
    })
    server.start()

    def address = server.getAddress()
    try (def client = new SignalClient(address, signalClientTimeoutMillis)) {
      client.send(signalA)
      client.send(signalB)
    }

    then:
    received.size() == 2
    received[0] == signalA
    received[1] == signalB

    cleanup:
    server.stop()
  }

  def "test multiple clients send and receive"() {
    given:
    def signalA = new ModuleExecutionResult(DDTraceId.from(123), 456, true, false, true, false, true, false, 1, Collections.singletonList(new TestFramework("junit", "4.13.2")))
    def signalB = new ModuleExecutionResult(DDTraceId.from(234), 567, false, true, false, true, false, false, 0, Collections.singletonList(new TestFramework("junit", "4.13.2")))
    def server = new SignalServer()
    def received = new ArrayList()

    when:
    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      received.add(it)
      return AckResponse.INSTANCE
    })
    server.start()

    def address = server.getAddress()
    try (def client = new SignalClient(address, signalClientTimeoutMillis)) {
      client.send(signalA)
    }

    try (def client = new SignalClient(address, signalClientTimeoutMillis)) {
      client.send(signalB)
    }

    then:
    received.size() == 2
    received[0] == signalA
    received[1] == signalB

    cleanup:
    server.stop()
  }

  def "test client socket timeout"() {
    given:
    def clientTimeoutMillis = 500
    def server = new SignalServer()

    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      try {
        Thread.sleep(clientTimeoutMillis * 2)
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt()
      }
      return AckResponse.INSTANCE
    })
    server.start()

    when:
    def address = server.getAddress()
    try (def client = new SignalClient(address, clientTimeoutMillis)) {
      client.send(new ModuleExecutionResult(DDTraceId.from(123), 456, false, false, false, false, false, false, 0, Collections.singletonList(new TestFramework("junit", "4.13.2"))))
    }

    then:
    thrown SocketTimeoutException

    cleanup:
    server.stop()
  }

  def "test error response receipt"() {
    given:
    def signal = new ModuleExecutionResult(DDTraceId.from(123), 456, true, true, false, false, true, false, 1, Collections.singletonList(new TestFramework("junit", "4.13.2")))
    def server = new SignalServer()

    def errorResponse = new ErrorResponse("An error occurred while processing the signal")
    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      return errorResponse
    })
    server.start()

    when:
    def address = server.getAddress()
    try (def client = new SignalClient(address, signalClientTimeoutMillis)) {
      client.send(signal)
    }

    then:
    def e = thrown(IOException)
    e.message == SignalClient.getErrorMessage(errorResponse)

    cleanup:
    server.stop()
  }
}
