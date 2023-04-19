import datadog.trace.instrumentation.iastinstrumenter.IastExclusionTrie
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic
import spock.lang.Unroll

@CompileDynamic
class IastExclusionTrieTest extends DDSpecification {

  private static final int EXCLUDED = 1
  private static final int BYPASSED = 0
  private static final int INCLUDED = -1

  @Unroll
  void 'Test that #name should be included? #expected'(final String name, final int expected) {
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
  }
}
