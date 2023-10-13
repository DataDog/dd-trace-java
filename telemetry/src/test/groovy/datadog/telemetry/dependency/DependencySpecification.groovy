package datadog.telemetry.dependency

import datadog.trace.test.util.DDSpecification

import java.util.jar.Attributes
import java.util.jar.Manifest

class DependencySpecification extends DDSpecification {

  void 'test guessFallbackNoPom with bundle-symbolicname = #bundleSymbolicName'() {
    given:
    final attributes = Mock(Attributes) {
      getValue('bundle-symbolicname') >> bundleSymbolicName
    }
    final manifest = Mock(Manifest) {
      getMainAttributes() >> attributes
    }
    final stream = Mock(InputStream)

    when:
    Dependency.guessFallbackNoPom(manifest, "abc", stream)

    then:
    noExceptionThrown()

    where:
    bundleSymbolicName              | _
    null                            | _
    ''                              | _
    'org.osgi.framework.bsnversion' | _
  }
}
