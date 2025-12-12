package datadog.smoketest

import datadog.environment.JavaVirtualMachine
import datadog.trace.test.util.Flaky

import static datadog.trace.api.config.IastConfig.*
import static datadog.trace.api.iast.IastContext.Mode.GLOBAL

class Jersey3SmokeTest extends AbstractJerseySmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    String jarPath = System.getProperty('datadog.smoketest.jersey3.jar.path')

    List<String> command = []
    command.add(javaPath())
    command.addAll(defaultJavaProperties)
    command.addAll(iastJvmOpts())
    command.add(withSystemProperty('integration.grizzly.enabled', true))
    command.addAll(['-jar', jarPath, Integer.toString(httpPort)])
    ProcessBuilder processBuilder = new ProcessBuilder(command)
    processBuilder.directory(new File(buildDirectory))
    return processBuilder
  }

  protected List<String> iastJvmOpts() {
    return [
      withSystemProperty(IAST_ENABLED, true),
      withSystemProperty(IAST_DETECTION_MODE, 'FULL'),
      withSystemProperty(IAST_DEBUG_ENABLED, true),
    ]
  }

  @Flaky(value = 'global context is flaky under IBM8', condition = () -> JavaVirtualMachine.isIbm8())
  static class WithGlobalContext extends Jersey3SmokeTest {
    @Override
    protected List<String> iastJvmOpts() {
      final opts = super.iastJvmOpts()
      opts.add(withSystemProperty(IAST_CONTEXT_MODE, GLOBAL.name()))
      return opts
    }
  }
}
