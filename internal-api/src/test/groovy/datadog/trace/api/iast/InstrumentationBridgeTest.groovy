package datadog.trace.api.iast

import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.StringModule
import datadog.trace.api.iast.sink.CommandInjectionModule
import datadog.trace.api.iast.sink.LdapInjectionModule
import datadog.trace.api.iast.sink.PathTraversalModule
import datadog.trace.api.iast.sink.SqlInjectionModule
import datadog.trace.api.iast.sink.SsrfModule
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.api.iast.sink.WeakCipherModule
import datadog.trace.api.iast.sink.WeakHashModule
import datadog.trace.api.iast.sink.WeakRandomnessModule
import datadog.trace.api.iast.sink.XPathInjectionModule
import datadog.trace.api.iast.source.WebModule
import datadog.trace.test.util.DDSpecification

class InstrumentationBridgeTest extends DDSpecification {

  private final static BRIDGE_MODULES = [
    WeakCipherModule,
    WeakHashModule,
    WebModule,
    StringModule,
    CodecModule,
    SqlInjectionModule,
    CommandInjectionModule,
    PathTraversalModule,
    LdapInjectionModule,
    SsrfModule,
    UnvalidatedRedirectModule,
    WeakRandomnessModule,
    XPathInjectionModule
  ]

  def cleanup() {
    InstrumentationBridge.clearIastModules()
  }

  void '#module can be registered'(final Class<? extends IastModule> module) {
    setup:
    final instance = Mock(module)
    InstrumentationBridge.registerIastModule(instance)

    when:
    def result = InstrumentationBridge.getIastModule(module)

    then:
    instance == result

    where:
    module << BRIDGE_MODULES
  }

  void 'unsupported modules throw exceptions'() {
    when:
    InstrumentationBridge.registerIastModule(Mock(IastModule))

    then:
    thrown(UnsupportedOperationException)

    when:
    InstrumentationBridge.getIastModule(IastModule)

    then:
    thrown(UnsupportedOperationException)
  }
}
