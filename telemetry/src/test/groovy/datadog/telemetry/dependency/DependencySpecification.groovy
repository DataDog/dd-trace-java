package datadog.telemetry.dependency

import datadog.trace.test.util.DDSpecification

import java.util.jar.Attributes
import java.util.jar.Manifest

class DependencySpecification extends DDSpecification {

  void 'test guessFallbackNoPom with bundle-symbolicname = #bundleSymbolicName'() {
    given:
    final attributes = Stub(Attributes) {
      getValue('bundle-symbolicname') >> bundleSymbolicName
    }
    final manifest = Stub(Manifest) {
      getMainAttributes() >> attributes
    }
    final stream = Stub(InputStream)

    when:
    def dep = Dependency.guessFallbackNoPom(manifest, "abc", stream)

    then:
    dep != null
    dep.name == bundleSymbolicName

    where:
    bundleSymbolicName              | _
    null                            | _
    ''                              | _
    'org.osgi.framework.bsnversion' | _
  }
}
