import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie
import datadog.trace.test.util.DDSpecification
import spock.lang.Unroll

class IastExclusionTrieTest extends DDSpecification {

  @Unroll
  def 'Test that #name should be included? #expected'(final String name, final int expected) {
    when:
    final result = IastExclusionTrie.apply(name)

    then:
    result == expected

    where:
    name                                                | expected
    String.name                                         | 1
    'io.vertx.core.json.JsonArray'                      | 1
    'io.vertx.demo.Test'                                | 0
    'org.springframework.core.env.Environment'          | 1
    'org.springframework.samples.petclinic.owner.Owner' | 0
    'org.owasp.encoder.Encode'                          | 1
    'org.owasp.webgoat.server.StartWebGoat'             | 0
    'my.package.name.Test'                              | -1
    'com.test.Demo'                                     | -1
  }
}
