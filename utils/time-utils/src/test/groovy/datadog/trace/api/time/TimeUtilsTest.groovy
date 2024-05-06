package datadog.trace.api.time

import datadog.trace.test.util.DDSpecification

class TimeUtilsTest extends DDSpecification {

  def "test simple delay parsing"() {
    when:
    long delay = TimeUtils.parseSimpleDelay(delayString)

    then:
    delay == expected

    where:
    delayString | expected
    null        | -1
    ""          | -1
    "foo"       | -1
    "-8"        | -1
    "-1"        | -1
    "0"         | 0
    "1"         | 1
    "2"         | 2
    "3"         | 3
    "0s"        | 0
    "1s"        | 1
    "2s"        | 2
    "3s"        | 3
    "0m"        | 0
    "1m"        | 60
    "2m"        | 120
    "3m"        | 180
    "0h"        | 0
    "1h"        | 3600
    "2h"        | 7200
    "3h"        | 10800
    "0S"        | 0
    "1S"        | 1
    "2S"        | 2
    "3S"        | 3
    "0M"        | 0
    "1M"        | 60
    "2M"        | 120
    "3M"        | 180
    "0H"        | 0
    "1H"        | 3600
    "2H"        | 7200
    "3H"        | 10800
  }
}
