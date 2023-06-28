package datadog.trace.civisibility.ipc

import spock.lang.Specification

import java.util.concurrent.atomic.AtomicBoolean

class SignalServerTest extends Specification {

  def "test message send and receive"() {
    given:
    def signalProcessed = new AtomicBoolean(false)
    def signal = new ModuleExecutionResult(123, 456, true, true, true)
    def server = new SignalServer()
    def received = new ArrayList()

    expect:
    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      received.add(it)
      signalProcessed.set(true)
    })
    server.start()

    def address = server.getAddress()
    try (def client = new SignalClient(address)) {
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
    def signalA = new ModuleExecutionResult(123, 456, false, false, false)
    def signalB = new ModuleExecutionResult(234, 567, true, true, true)
    def server = new SignalServer()
    def received = new ArrayList()

    expect:
    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      received.add(it)
    })
    server.start()

    def address = server.getAddress()
    try (def client = new SignalClient(address)) {
      client.send(signalA)
      client.send(signalB)
    }

    received.size() == 2
    received[0] == signalA
    received[1] == signalB

    cleanup:
    server.stop()
  }

  def "test multiple clients send and receive"() {
    given:
    def signalA = new ModuleExecutionResult(123, 456, true, false, true)
    def signalB = new ModuleExecutionResult(234, 567, false, true, false)
    def server = new SignalServer()
    def received = new ArrayList()

    expect:
    server.registerSignalHandler(SignalType.MODULE_EXECUTION_RESULT, {
      received.add(it)
    })
    server.start()

    def address = server.getAddress()
    try (def client = new SignalClient(address)) {
      client.send(signalA)
    }

    try (def client = new SignalClient(address)) {
      client.send(signalB)
    }

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
    })
    server.start()

    when:
    def address = server.getAddress()
    try (def client = new SignalClient(address, clientTimeoutMillis)) {
      client.send(new ModuleExecutionResult(123, 456, false, false, false))
    }

    then:
    thrown SocketTimeoutException

    cleanup:
    server.stop()
  }
}
