import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie

import java.lang.reflect.Proxy

class IastExclusionTrieTest extends InstrumentationSpecification {

  private static final int STACK_FILTERED = 2
  private static final int EXCLUDED = 1
  private static final int BYPASSED = 0
  private static final int INCLUDED = -1

  void 'Test that #name should be included? #expected'() {
    when:
    final result = IastExclusionTrie.apply(name)

    then:
    result == expected

    where:
    name                                                | expected
    String.name                                         | EXCLUDED
    'io.vertx.core.json.JsonArray'                      | EXCLUDED
    'io.vertx.demo.Test'                                | BYPASSED
    'org.springframework.core.env.Environment'          | EXCLUDED
    'org.springframework.samples.petclinic.owner.Owner' | BYPASSED
    'org.owasp.encoder.Encode'                          | EXCLUDED
    'org.owasp.webgoat.server.StartWebGoat'             | BYPASSED
    'my.package.name.Test'                              | INCLUDED
    'com.test.Demo'                                     | INCLUDED
    'software.amazon.awssdk.core.checksums.Md5Checksum' | EXCLUDED
    'com.mongodb.internal.HexUtils'                     | EXCLUDED
    'com.squareup.okhttp.Request'                       | STACK_FILTERED
  }

  void 'test with java proxies'() {
    when:
    final result = IastExclusionTrie.apply(proxy.class.name)

    then:
    result == EXCLUDED

    where:
    proxy                                         | _
    createProxy(Comparable)                       | _
    createProxy(Comparable, Cloneable, Closeable) | _
  }

  private static Object createProxy(final Class<?>... interfaces) {
    return Proxy.newProxyInstance(Thread.currentThread().contextClassLoader, interfaces) { proxy, method, args ->
      return null
    }
  }
}
