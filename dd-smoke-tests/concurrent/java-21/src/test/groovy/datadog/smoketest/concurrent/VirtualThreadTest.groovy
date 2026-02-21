package datadog.smoketest.concurrent

class VirtualThreadTest extends AbstractConcurrentTest {
  def 'test Thread.startVirtualThread() runnable'() {
    expect:
    receivedCorrectTrace('virtualThreadStart')
  }

  def 'test VirtualThread execute runnable'() {
    expect:
    receivedCorrectTrace('virtualThreadExecute')
  }

  def 'test VirtualThread invokeAll callable'() {
    expect:
    receivedCorrectTrace('virtualThreadInvokeAll')
  }

  def 'test VirtualThread invoke any callable'() {
    expect:
    receivedCorrectTrace('virtualThreadInvokeAny')
  }

  def 'test VirtualThread submit runnable'() {
    expect:
    receivedCorrectTrace('virtualThreadSubmitRunnable')
  }

  def 'test VirtualThread submit callable'() {
    expect:
    receivedCorrectTrace('virtualThreadSubmitCallable')
  }
}
