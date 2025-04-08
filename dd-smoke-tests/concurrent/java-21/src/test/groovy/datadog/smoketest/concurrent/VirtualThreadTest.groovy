package datadog.smoketest.concurrent

class VirtualThreadExecuteTest extends AbstractConcurrentTest {
  @Override
  protected List<String> getTestArguments() {
    return ['virtualThreadExecute']
  }

  def 'test VirtualThread execute runnable'() {
    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadInvokeAllTest extends AbstractConcurrentTest {
  @Override
  protected List<String> getTestArguments() {
    return ['virtualThreadInvokeAll']
  }

  def 'test VirtualThread invokeAll callable'() {
    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadInvokeAnyTest extends AbstractConcurrentTest {
  @Override
  protected List<String> getTestArguments() {
    return ['virtualThreadInvokeAny']
  }

  def 'test VirtualThread invoke any callable'() {
    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadSubmitRunnableTest extends AbstractConcurrentTest {
  @Override
  protected List<String> getTestArguments() {
    return ['virtualThreadSubmitRunnable']
  }

  def 'test VirtualThread submit runnable'() {
    expect:
    receivedCorrectTrace()
  }
}

class VirtualThreadSubmitCallableTest extends AbstractConcurrentTest {
  @Override
  protected List<String> getTestArguments() {
    return ['virtualThreadSubmitCallable']
  }

  def 'test VirtualThread submit callable'() {
    expect:
    receivedCorrectTrace()
  }
}
