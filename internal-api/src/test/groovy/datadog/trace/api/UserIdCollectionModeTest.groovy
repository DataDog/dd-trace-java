package datadog.trace.api

import spock.lang.Specification

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION
import static datadog.trace.api.UserIdCollectionMode.DISABLED
import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION

class UserIdCollectionModeTest extends Specification {

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
}
