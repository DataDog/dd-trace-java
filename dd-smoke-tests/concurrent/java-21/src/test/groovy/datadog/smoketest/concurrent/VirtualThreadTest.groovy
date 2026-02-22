package datadog.smoketest.concurrent

class VirtualThreadStartTest extends AbstractConcurrentTest {
  def 'test Thread.startVirtualThread() runnable'() {
    setup:
    sendScenarioSignal('virtualThreadStart')

    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadExecuteTest extends AbstractConcurrentTest {
  def 'test VirtualThread execute runnable'() {
    setup:
    sendScenarioSignal('virtualThreadExecute')

    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadInvokeAllTest extends AbstractConcurrentTest {
  def 'test VirtualThread invokeAll callable'() {
    setup:
    sendScenarioSignal('virtualThreadInvokeAll')

    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadInvokeAnyTest extends AbstractConcurrentTest {
  def 'test VirtualThread invoke any callable'() {
    setup:
    sendScenarioSignal('virtualThreadInvokeAny')

    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadSubmitRunnableTest extends AbstractConcurrentTest {
  def 'test VirtualThread submit runnable'() {
    setup:
    sendScenarioSignal('virtualThreadSubmitRunnable')

    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadSubmitCallableTest extends AbstractConcurrentTest {
  def 'test VirtualThread submit callable'() {
    setup:
    sendScenarioSignal('virtualThreadSubmitCallable')

    expect:
    receivedCorrectTrace()
  }
}
