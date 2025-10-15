package com.datadog.aiguard

import datadog.trace.api.aiguard.AIGuard
import datadog.trace.test.util.DDSpecification

class AIGuardSystemTests extends DDSpecification {

  void cleanup() {
    AIGuardInternal.uninstall()
  }

  void 'test SDK initialization'() {
    injectEnvConfig('API_KEY', 'api')
    injectEnvConfig('APP_KEY', 'app')

    when:
    AIGuardSystem.start()

    then:
    AIGuard.EVALUATOR instanceof AIGuardInternal
  }
}
