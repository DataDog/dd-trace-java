package datadog.smoketest

abstract class MultipleAgentGuardrailsTest extends AbstractSmokeTest {
  static final String LIB_INJECTION_ENABLED_FLAG = 'DD_INJECTION_ENABLED'
  static final String LIB_INJECTION_FORCE_FLAG = 'DD_INJECT_FORCE'

  @Override
  ProcessBuilder createProcessBuilder() {
    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    def command = []
    command+= javaPath()
    command.addAll(defaultJavaProperties)
    command+= "-javaagent:${jarPath}" as String // Happen the fake agent too
    command+= '-Ddd.integration.opentelemetry.experimental.enabled=true'
    command+= '-jar'
    command+= jarPath

    ProcessBuilder processBuilder = new ProcessBuilder(command)
    if (isLibInjectionEnabled()) {
      processBuilder.environment().put(LIB_INJECTION_ENABLED_FLAG, 'TRUE')
    }
    if (isLibInjectionForced()) {
      processBuilder.environment().put(LIB_INJECTION_FORCE_FLAG, 'TRUE')
    }
    processBuilder.directory(new File(buildDirectory))
  }

  abstract boolean isLibInjectionEnabled()
  abstract boolean isLibInjectionForced()

  boolean isExpectingTrace() {
    return !isLibInjectionEnabled() || isLibInjectionForced()
  }

  def 'receive trace'() {
    expect:
    if (isExpectingTrace()) {
      waitForTraceCount(1)
    } else {
      Thread.sleep(10_000)
      traceCount.get() == 30
    }
  }
}

class LibInjectionDisabledTest extends MultipleAgentGuardrailsTest {
  @Override
  boolean isLibInjectionEnabled() {
    return false
  }

  @Override
  boolean isLibInjectionForced() {
    return false
  }
}

class LibInjectionEnabledTest extends MultipleAgentGuardrailsTest {
  @Override
  boolean isLibInjectionEnabled() {
    return true
  }

  @Override
  boolean isLibInjectionForced() {
    return false
  }
}

class LibInjectionForcedTest extends MultipleAgentGuardrailsTest {
  @Override
  boolean isLibInjectionEnabled() {
    return true
  }

  @Override
  boolean isLibInjectionForced() {
    return false
  }
}
