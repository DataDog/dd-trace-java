package datadog.trace.api.iast

import datadog.trace.api.ConfigDefaults
import ConfigProvider
import groovy.transform.CompileDynamic
import spock.lang.Specification

import static datadog.trace.api.iast.IastDetectionMode.UNLIMITED

@CompileDynamic
class IastDetectionModeTest extends Specification {

  void 'test max concurrent requests'() {
    given:
    final config = ConfigProvider.getInstance()

    when:
    final result = mode.getIastMaxConcurrentRequests(config)

    then:
    result == expected

    where:
    mode                      | expected
    IastDetectionMode.FULL    | UNLIMITED
    IastDetectionMode.DEFAULT | ConfigDefaults.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS
  }

  void 'test vulnerabilities per requests'() {
    given:
    final config = ConfigProvider.getInstance()

    when:
    final result = mode.getIastVulnerabilitiesPerRequest(config)

    then:
    result == expected

    where:
    mode                      | expected
    IastDetectionMode.FULL    | UNLIMITED
    IastDetectionMode.DEFAULT | ConfigDefaults.DEFAULT_IAST_VULNERABILITIES_PER_REQUEST
  }

  void 'test sampling'() {
    given:
    final config = ConfigProvider.getInstance()

    when:
    final result = mode.getIastRequestSampling(config)

    then:
    result == expected

    where:
    mode                      | expected
    IastDetectionMode.FULL    | 100
    IastDetectionMode.DEFAULT | ConfigDefaults.DEFAULT_IAST_REQUEST_SAMPLING
  }

  void 'test deduplication'() {
    given:
    final config = ConfigProvider.getInstance()

    when:
    final result = mode.isIastDeduplicationEnabled(config)

    then:
    result == expected

    where:
    mode                      | expected
    IastDetectionMode.FULL    | false
    IastDetectionMode.DEFAULT | ConfigDefaults.DEFAULT_IAST_DEDUPLICATION_ENABLED
  }
}
