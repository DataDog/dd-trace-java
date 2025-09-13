package datadog.trace.api.rum

import spock.lang.Specification

class RumTelemetryCollectorTest extends Specification {

  def "test default NO_OP does not throw exception"() {
    when:
    RumTelemetryCollector.NO_OP.onInjectionSucceed("3")
    RumTelemetryCollector.NO_OP.onInjectionSucceed("5")
    RumTelemetryCollector.NO_OP.onInjectionFailed("3", "gzip")
    RumTelemetryCollector.NO_OP.onInjectionFailed("5", null)
    RumTelemetryCollector.NO_OP.onInjectionSkipped("3")
    RumTelemetryCollector.NO_OP.onInjectionSkipped("5")
    RumTelemetryCollector.NO_OP.onInitializationSucceed()
    RumTelemetryCollector.NO_OP.onContentSecurityPolicyDetected("3")
    RumTelemetryCollector.NO_OP.onContentSecurityPolicyDetected("5")
    RumTelemetryCollector.NO_OP.onInjectionResponseSize("3", 256L)
    RumTelemetryCollector.NO_OP.onInjectionResponseSize("5", 512L)
    RumTelemetryCollector.NO_OP.onInjectionTime("3", 10L)
    RumTelemetryCollector.NO_OP.onInjectionTime("5", 20L)
    RumTelemetryCollector.NO_OP.close()

    then:
    noExceptionThrown()
  }

  def "test default NO_OP close method does not throw exception"() {
    when:
    RumTelemetryCollector.NO_OP.close()

    then:
    noExceptionThrown()
  }

  def "test defining a custom implementation does not throw exception"() {
    setup:
    def customCollector = new RumTelemetryCollector() {
        @Override
        void onInjectionSucceed(String integrationVersion) {
        }

        @Override
        void onInjectionFailed(String integrationVersion, String contentEncoding) {
        }

        @Override
        void onInjectionSkipped(String integrationVersion) {
        }

        @Override
        void onInitializationSucceed() {
        }

        @Override
        void onContentSecurityPolicyDetected(String integrationVersion) {
        }

        @Override
        void onInjectionResponseSize(String integrationVersion, long bytes) {
        }

        @Override
        void onInjectionTime(String integrationVersion, long milliseconds) {
        }
      }

    when:
    customCollector.close()

    then:
    noExceptionThrown()
  }

  def "test multiple close calls do not throw exception"() {
    when:
    RumTelemetryCollector.NO_OP.close()
    RumTelemetryCollector.NO_OP.close()
    RumTelemetryCollector.NO_OP.close()

    then:
    noExceptionThrown()
  }
}
