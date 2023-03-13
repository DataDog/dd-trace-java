import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.LdapInjectionModule
import foo.bar.TestDirContextSuite
import foo.bar.TestInitialDirContextSuite
import groovy.transform.CompileDynamic

import javax.naming.Name
import javax.naming.directory.InitialDirContext
import javax.naming.directory.SearchControls

@CompileDynamic
class DirContextCallSiteTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }


  def 'test search(String, String, SearchControls)'() {
    setup:
    final name = 'name'
    final filter = 'filter'
    final cons = Mock(SearchControls)
    final initialDirContext = Mock(InitialDirContext)
    initialDirContext.search(name, filter, cons) >> null
    final suite = suiteBuilder.call(initialDirContext)
    final iastModule = Mock(LdapInjectionModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    suite.search(name, filter, cons)

    then:
    1 * iastModule.onDirContextSearch(name, filter, null)
    1 * initialDirContext.search(name, filter, cons)
    0 * _

    where:
    _ | suiteBuilder
    _ | { InitialDirContext ctx -> new TestDirContextSuite(ctx) }
    _ | { InitialDirContext ctx -> new TestInitialDirContextSuite(ctx) }
  }

  def 'test search(Name, String, SearchControls)'() {
    setup:
    final name = Mock(Name)
    final filter = 'filter'
    final cons = Mock(SearchControls)
    final initialDirContext = Mock(InitialDirContext)
    initialDirContext.search(name, filter, cons) >> null
    final suite = suiteBuilder.call(initialDirContext)
    final iastModule = Mock(LdapInjectionModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    suite.search(name, filter, cons)

    then:
    1 * iastModule.onDirContextSearch(null, filter, null)
    1 * initialDirContext.search(name, filter, cons)
    0 * _

    where:
    _ | suiteBuilder
    _ | { InitialDirContext ctx -> new TestDirContextSuite(ctx) }
    _ | { InitialDirContext ctx -> new TestInitialDirContextSuite(ctx) }
  }

  def 'test search(String, String, Object[], SearchControls)'() {
    setup:
    final name = 'name'
    final filter = 'filter'
    final args = new Object[1]
    final cons = Mock(SearchControls)
    final initialDirContext = Mock(InitialDirContext)
    initialDirContext.search(name, filter, args, cons) >> null
    final suite = suiteBuilder.call(initialDirContext)
    final iastModule = Mock(LdapInjectionModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    suite.search(name, filter, args, cons)

    then:
    1 * iastModule.onDirContextSearch(name, filter, args)
    1 * initialDirContext.search(name, filter, args, cons)
    0 * _

    where:
    _ | suiteBuilder
    _ | { InitialDirContext ctx -> new TestDirContextSuite(ctx) }
    _ | { InitialDirContext ctx -> new TestInitialDirContextSuite(ctx) }
  }

  def 'test search(Name, String, Object[], SearchControls)'() {
    setup:
    final name = Mock(Name)
    final filter = 'filter'
    final args = new Object[1]
    final cons = Mock(SearchControls)
    final initialDirContext = Mock(InitialDirContext)
    initialDirContext.search(name, filter, args, cons) >> null
    final suite = suiteBuilder.call(initialDirContext)
    final iastModule = Mock(LdapInjectionModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    suite.search(name, filter, args, cons)

    then:
    1 * iastModule.onDirContextSearch(null, filter, args)
    1 * initialDirContext.search(name, filter, args, cons)
    0 * _

    where:
    _ | suiteBuilder
    _ | { InitialDirContext ctx -> new TestDirContextSuite(ctx) }
    _ | { InitialDirContext ctx -> new TestInitialDirContextSuite(ctx) }
  }
}
