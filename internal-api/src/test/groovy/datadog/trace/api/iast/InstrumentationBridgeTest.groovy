package datadog.trace.api.iast

import datadog.trace.api.iast.propagation.CodecModule
import datadog.trace.api.iast.propagation.StringModule
import datadog.trace.api.iast.sink.*
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
    WeakRandomnessModule
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

  void 'registered HttpHeaderModules are called on header callback'() {
    setup:
    final insecureCookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(insecureCookieModule)
    final unvalidatedRedirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(unvalidatedRedirectModule)

    when:
    InstrumentationBridge.onHeader("name", "value")

    then:
    1 * unvalidatedRedirectModule.onHeader("name", "value")
  }

  void 'Cookie modules  are called on header callback'() {
    setup:
    final insecureCookieModule = Mock(InsecureCookieModule)
    InstrumentationBridge.registerIastModule(insecureCookieModule)
    final noHttpOnlyCookieModule = Mock(NoHttpOnlyCookieModule)
    InstrumentationBridge.registerIastModule(noHttpOnlyCookieModule)
    final unvalidatedRedirectModule = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(unvalidatedRedirectModule)

    when:
    InstrumentationBridge.onHeader("Set-Cookie", "UserId=1")

    then:
    1 * insecureCookieModule.onCookies(_)
    1 * noHttpOnlyCookieModule.onCookies(_)
    1 * unvalidatedRedirectModule.onHeader("Set-Cookie", "UserId=1")
  }

  void 'unregistered HttpHeaderModules are not called on header callback'() {
    setup:
    final unvalidatedRedirectModule = Mock(UnvalidatedRedirectModule)

    when:
    InstrumentationBridge.onHeader("name", "value")

    then:
    0 * unvalidatedRedirectModule.onHeader("name", "value")
  }
}
