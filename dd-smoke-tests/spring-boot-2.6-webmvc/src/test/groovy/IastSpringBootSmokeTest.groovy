import datadog.smoketest.AbstractIastSpringBootTest
import datadog.trace.api.config.IastConfig

import static datadog.trace.api.iast.IastContext.Mode.GLOBAL

class IastSpringBootSmokeTest extends AbstractIastSpringBootTest {

  static class WithGlobalContext extends IastSpringBootSmokeTest {
    @Override
    protected List<String> iastJvmOpts() {
      final opts = super.iastJvmOpts()
      opts.add(withSystemProperty(IastConfig.IAST_CONTEXT_MODE, GLOBAL.name()))
      return opts
    }
  }
}
