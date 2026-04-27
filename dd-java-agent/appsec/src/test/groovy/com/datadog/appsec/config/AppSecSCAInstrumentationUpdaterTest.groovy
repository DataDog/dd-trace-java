package com.datadog.appsec.config

import datadog.trace.test.util.DDSpecification

import java.lang.instrument.Instrumentation

class AppSecSCAInstrumentationUpdaterTest extends DDSpecification {

  Instrumentation instrumentation

  void setup() {
    instrumentation = Mock(Instrumentation) {
      isRetransformClassesSupported() >> true
    }
  }

  def "constructor throws exception when instrumentation is null"() {
    when:
    new AppSecSCAInstrumentationUpdater(null)

    then:
    thrown(IllegalArgumentException)
  }

  def "constructor throws exception when retransformation is not supported"() {
    given:
    def unsupportedInstrumentation = Mock(Instrumentation) {
      isRetransformClassesSupported() >> false
    }

    when:
    new AppSecSCAInstrumentationUpdater(unsupportedInstrumentation)

    then:
    thrown(IllegalStateException)
  }

  def "constructor succeeds with valid instrumentation"() {
    when:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)

    then:
    updater != null
    updater.getCurrentConfig() == null
    !updater.hasTransformer()
  }

  def "onConfigUpdate with null config does not install transformer"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)

    when:
    updater.onConfigUpdate(null)

    then:
    0 * instrumentation.addTransformer(_, _)
    0 * instrumentation.retransformClasses(_)
    updater.getCurrentConfig() == null
    !updater.hasTransformer()
  }

  def "onConfigUpdate with empty vulnerabilities does not install transformer"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def config = new AppSecSCAConfig(vulnerabilities: [])

    when:
    updater.onConfigUpdate(config)

    then:
    0 * instrumentation.addTransformer(_, _)
    0 * instrumentation.retransformClasses(_)
    updater.getCurrentConfig() == config
    !updater.hasTransformer()
  }

  def "onConfigUpdate with valid config installs transformer"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def config = createConfigWithOneVulnerability("com.example.VulnerableClass")
    instrumentation.getAllLoadedClasses() >> []

    when:
    updater.onConfigUpdate(config)

    then:
    1 * instrumentation.addTransformer(_, true)
    updater.getCurrentConfig() == config
    updater.hasTransformer()
  }

  def "onConfigUpdate retransforms loaded classes matching targets"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    // Use String as a real class for testing
    def targetClassName = "java.lang.String"
    def config = createConfigWithOneVulnerability(targetClassName)

    instrumentation.getAllLoadedClasses() >> [String]
    instrumentation.isModifiableClass(String) >> true

    when:
    updater.onConfigUpdate(config)

    then:
    1 * instrumentation.addTransformer(_, true)
    1 * instrumentation.retransformClasses(String)
  }

  def "onConfigUpdate does not retransform non-modifiable classes"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def targetClassName = "java.lang.String"
    def config = createConfigWithOneVulnerability(targetClassName)

    instrumentation.getAllLoadedClasses() >> [String]
    instrumentation.isModifiableClass(String) >> false

    when:
    updater.onConfigUpdate(config)

    then:
    1 * instrumentation.addTransformer(_, true)
    0 * instrumentation.retransformClasses(_)
  }

  def "onConfigUpdate does not retransform classes that don't match targets"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def config = createConfigWithOneVulnerability("java.lang.String")

    // Use Integer which does NOT match the target (String)
    instrumentation.getAllLoadedClasses() >> [Integer]

    when:
    updater.onConfigUpdate(config)

    then:
    1 * instrumentation.addTransformer(_, true)
    0 * instrumentation.retransformClasses(_)
  }

  def "onConfigUpdate only retransforms NEW targets (additive-only approach)"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def class1 = "java.lang.String"
    def class2 = "java.lang.Integer"

    when:
    // First config with one vulnerability
    def config1 = createConfigWithOneVulnerability(class1)
    instrumentation.getAllLoadedClasses() >> [String]
    instrumentation.isModifiableClass(String) >> true
    updater.onConfigUpdate(config1)

    then:
    // Transformer was installed on first config
    1 * instrumentation.addTransformer(_, true)
    1 * instrumentation.retransformClasses(String)

    when:
    // Second config adds another vulnerability
    def config2 = createConfigWithTwoVulnerabilities(class1, class2)
    instrumentation.getAllLoadedClasses() >> [String, Integer]
    instrumentation.isModifiableClass(Integer) >> true
    updater.onConfigUpdate(config2)

    then:
    // Transformer should NOT be installed again
    0 * instrumentation.addTransformer(_, _)
    // Only the NEW class (Integer) should be retransformed
    1 * instrumentation.retransformClasses(Integer)
    // String should NOT be retransformed again
    0 * instrumentation.retransformClasses(String)
  }

  def "onConfigUpdate handles retransformation exceptions gracefully"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def targetClassName = "java.lang.String"
    def config = createConfigWithOneVulnerability(targetClassName)

    instrumentation.getAllLoadedClasses() >> [String]
    instrumentation.isModifiableClass(String) >> true
    instrumentation.retransformClasses(String) >> { throw new RuntimeException("Test exception") }

    when:
    updater.onConfigUpdate(config)

    then:
    notThrown(Exception)
    1 * instrumentation.addTransformer(_, true)
  }

  def "onConfigUpdate with null config after valid config keeps transformer installed"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    instrumentation.getAllLoadedClasses() >> []

    when:
    // First, apply valid config
    def config = createConfigWithOneVulnerability("java.lang.String")
    updater.onConfigUpdate(config)

    then:
    // Verify transformer was installed
    1 * instrumentation.addTransformer(_, true)

    when:
    // Then remove config
    updater.onConfigUpdate(null)

    then:
    // Transformer should never be removed
    0 * instrumentation.removeTransformer(_)
    updater.getCurrentConfig() == null
    updater.hasTransformer() // Transformer still installed
  }

  def "onConfigUpdate with multiple vulnerabilities extracts all target classes"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def class1 = "java.lang.String"
    def class2 = "java.lang.Integer"
    def config = createConfigWithTwoVulnerabilities(class1, class2)

    instrumentation.getAllLoadedClasses() >> [String, Integer]
    instrumentation.isModifiableClass(_) >> true

    when:
    updater.onConfigUpdate(config)

    then:
    1 * instrumentation.addTransformer(_, true)
    1 * instrumentation.retransformClasses(String)
    1 * instrumentation.retransformClasses(Integer)
  }

  def "getCurrentConfig returns current configuration"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def config = createConfigWithOneVulnerability("com.example.VulnerableClass")
    instrumentation.getAllLoadedClasses() >> []

    when:
    updater.onConfigUpdate(config)

    then:
    updater.getCurrentConfig() == config
  }

  def "hasTransformer returns false before any config update"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)

    expect:
    !updater.hasTransformer()
  }

  def "hasTransformer returns true after valid config update"() {
    given:
    def updater = new AppSecSCAInstrumentationUpdater(instrumentation)
    def config = createConfigWithOneVulnerability("com.example.VulnerableClass")
    instrumentation.getAllLoadedClasses() >> []

    when:
    updater.onConfigUpdate(config)

    then:
    updater.hasTransformer()
  }

  // Helper methods to create test configs

  private AppSecSCAConfig createConfigWithOneVulnerability(String className) {
    def entrypoint = new AppSecSCAConfig.ExternalEntrypoint(
      className: className,
      methods: ["vulnerableMethod"]
      )
    def vulnerability = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-yyyy-zzzz",
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint
      )
    return new AppSecSCAConfig(vulnerabilities: [vulnerability])
  }

  private AppSecSCAConfig createConfigWithTwoVulnerabilities(String className1, String className2) {
    def entrypoint1 = new AppSecSCAConfig.ExternalEntrypoint(
      className: className1,
      methods: ["vulnerableMethod1"]
      )
    def vulnerability1 = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-xxxx-yyyy-zzzz",
      cve: "CVE-2024-0001",
      externalEntrypoint: entrypoint1
      )

    def entrypoint2 = new AppSecSCAConfig.ExternalEntrypoint(
      className: className2,
      methods: ["vulnerableMethod2"]
      )
    def vulnerability2 = new AppSecSCAConfig.Vulnerability(
      advisory: "GHSA-aaaa-bbbb-cccc",
      cve: "CVE-2024-0002",
      externalEntrypoint: entrypoint2
      )

    return new AppSecSCAConfig(vulnerabilities: [vulnerability1, vulnerability2])
  }
}
