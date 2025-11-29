import datadog.environment.JavaVirtualMachine
import datadog.smoketest.AbstractIastSpringBootTest
import datadog.trace.api.config.IastConfig
import datadog.trace.test.util.Flaky

import static datadog.trace.api.iast.IastContext.Mode.GLOBAL

class IastSpringBootSmokeTest extends AbstractIastSpringBootTest {

  @Flaky(value = 'global context is flaky under IBM8', condition = () -> JavaVirtualMachine.isIbm8())
  static class WithGlobalContext extends IastSpringBootSmokeTest {
    @Override
    protected List<String> iastJvmOpts() {
      final opts = super.iastJvmOpts()
      opts.add(withSystemProperty(IastConfig.IAST_CONTEXT_MODE, GLOBAL.name()))
      return opts
    }
  }
}
