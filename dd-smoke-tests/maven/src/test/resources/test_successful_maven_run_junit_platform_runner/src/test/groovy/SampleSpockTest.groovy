package test_successful_maven_run_junit_platform_runner.src.test.groovy

import org.junit.platform.runner.JUnitPlatform
import org.junit.runner.RunWith
import spock.lang.Specification

@RunWith(JUnitPlatform.class)
class SampleSpockTest extends Specification {

  def "test should pass"() {
    expect:
    1 == 1
  }

}
