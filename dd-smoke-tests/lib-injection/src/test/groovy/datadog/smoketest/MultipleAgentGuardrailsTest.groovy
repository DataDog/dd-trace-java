package datadog.smoketest

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.Paths


abstract class MultipleAgentGuardrailsTest extends AbstractSmokeTest {
  static final String LIB_INJECTION_ENABLED_FLAG = 'DD_INJECTION_ENABLED'
  static final String LIB_INJECTION_FORCE_FLAG = 'DD_INJECT_FORCE'

  @Override
  ProcessBuilder createProcessBuilder() {

    def jarPath = System.getProperty("datadog.smoketest.shadowJar.path")
    if (extraAgentFilename() != null) {
      def renamedJar = Paths.get(jarPath).getParent().resolve(extraAgentFilename())
      Files.copy(Paths.get(jarPath), renamedJar, StandardCopyOption.REPLACE_EXISTING)
      jarPath = renamedJar.toString()
    }
    def command = []
    command+= javaPath()
    command.addAll(defaultJavaProperties)
    command+= "-javaagent:${jarPath}" as String // Happen the fake agent too
    command+= '-Ddd.trace.otel.enabled=true'
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

  String extraAgentFilename() {
    return null
  }


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
    return true
  }
}

// Test that injection still works if we have to agent if one of them is the aws emr log4j patcher
class LibsInjectionWorksEmr extends MultipleAgentGuardrailsTest {
  @Override
  boolean isLibInjectionEnabled() {
    return true
  }

  @Override
  boolean isLibInjectionForced() {
    return false
  }

  @Override
  String extraAgentFilename() {
    return "Log4jHotPatchFat.jar"
  }

  @Override
  boolean isExpectingTrace() {
    return true
  }
}
