package datadog.trace.api

import datadog.trace.api.config.AppSecConfig
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION
import static datadog.trace.api.UserIdCollectionMode.DISABLED
import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION

class UserIdCollectionModeTest extends DDSpecification {

  void 'test user id collection mode "#mode"'() {
    when:
    def result = UserIdCollectionMode.fromString(mode, null)

    then:
    result == expected

    where:
    mode                          | expected
    null                          | IDENTIFICATION
    'identification'              | IDENTIFICATION
    'iDeNTiFiCaTioN'              | IDENTIFICATION
    'ident'                       | IDENTIFICATION
    'anonymization'               | ANONYMIZATION
    'aNoNyMiZaTioN'               | ANONYMIZATION
    'anon'                        | ANONYMIZATION
    'disabled'                    | DISABLED
    'dIsAblEd'                    | DISABLED
    ''                            | DISABLED
    'go loves node but java wins' | DISABLED
  }

  void 'test user id collection mode "#mode" with tracking "#tracking"'() {
    when:
    def result = UserIdCollectionMode.fromString(mode, tracking)

    then:
    result == expected

    where:
    mode    | tracking   | expected
    // when value is not defined
    null    | 'extended' | IDENTIFICATION
    null    | 'safe'     | ANONYMIZATION
    null    | 'disabled' | DISABLED
    // actual value takes priority
    'ident' | null       | IDENTIFICATION
    'ident' | 'extended' | IDENTIFICATION
    'ident' | 'safe'     | IDENTIFICATION
    'ident' | 'disabled' | IDENTIFICATION
    'anon'  | null       | ANONYMIZATION
    'anon'  | 'extended' | ANONYMIZATION
    'anon'  | 'safe'     | ANONYMIZATION
    'anon'  | 'disabled' | ANONYMIZATION
    // by default
    null    | null       | IDENTIFICATION
  }

  void 'test user id collection mode "#mode" with remote config "#rc"'() {
    setup:
    if (mode == null) {
      removeSysConfig(AppSecConfig.APPSEC_AUTO_USER_INSTRUMENTATION_MODE)
    } else {
      injectSysConfig(AppSecConfig.APPSEC_AUTO_USER_INSTRUMENTATION_MODE, mode)
    }

    when:
    UserIdCollectionMode.fromRemoteConfig(rc)

    then:
    UserIdCollectionMode.get() == expected

    where:
    mode       | rc         | expected
    // by default
    null       | null       | IDENTIFICATION
    // rc wins
    'ident'    | 'yolo'     | DISABLED
    'ident'    | 'disabled' | DISABLED
    'anon'     | 'ident'    | IDENTIFICATION
    'ident'    | 'anon'     | ANONYMIZATION
    // rc not present
    'yolo'     | null       | DISABLED
    'disabled' | null       | DISABLED
    'ident'    | null       | IDENTIFICATION
    'anon'     | null       | ANONYMIZATION
  }
}
