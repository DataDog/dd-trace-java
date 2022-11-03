package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.ConfigTest.PREFIX
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_DEDUPLICATION_ENABLED
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_ENABLED
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_MAX_CONCURRENT_REQUESTS
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_REQUEST_SAMPLING
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS
import static datadog.trace.api.config.IastConfig.DEFAULT_IAST_WEAK_HASH_ALGORITHMS
import static datadog.trace.api.config.IastConfig.IAST_DEDUPLICATION_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_ENABLED
import static datadog.trace.api.config.IastConfig.IAST_MAX_CONCURRENT_REQUESTS
import static datadog.trace.api.config.IastConfig.IAST_REQUEST_SAMPLING
import static datadog.trace.api.config.IastConfig.IAST_VULNERABILITIES_PER_REQUEST
import static datadog.trace.api.config.IastConfig.IAST_WEAK_CIPHER_ALGORITHMS
import static datadog.trace.api.config.IastConfig.IAST_WEAK_HASH_ALGORITHMS

class IastConfigTest extends DDSpecification {
  def "check default config values"() {
    when:
    def config = new Config()

    then:
    config.iastWeakHashAlgorithms == DEFAULT_IAST_WEAK_HASH_ALGORITHMS
    config.iastWeakCipherAlgorithms.pattern() == DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS
    config.iastDeduplicationEnabled == DEFAULT_IAST_DEDUPLICATION_ENABLED
    config.iastEnabled == DEFAULT_IAST_ENABLED
    config.iastMaxConcurrentRequests == DEFAULT_IAST_MAX_CONCURRENT_REQUESTS
    config.iastVulnerabilitiesPerRequest == DEFAULT_IAST_MAX_CONCURRENT_REQUESTS
    config.iastRequestSampling == DEFAULT_IAST_REQUEST_SAMPLING
  }

  def "check overridden config values"() {
    setup:
    System.setProperty(PREFIX + IAST_WEAK_HASH_ALGORITHMS, "MD2,MD4,MD2")
    def weekCypherAlgorithms = "(BLOWFISH|ARCFOUR|RC2).*"
    System.setProperty(PREFIX + IAST_WEAK_CIPHER_ALGORITHMS, weekCypherAlgorithms)
    System.setProperty(PREFIX + IAST_DEDUPLICATION_ENABLED, "false")
    System.setProperty(PREFIX + IAST_ENABLED, "true")
    System.setProperty(PREFIX + IAST_MAX_CONCURRENT_REQUESTS, "5")
    System.setProperty(PREFIX + IAST_VULNERABILITIES_PER_REQUEST, "6")
    System.setProperty(PREFIX + IAST_REQUEST_SAMPLING, "50")

    when:
    def config = new Config()

    then:
    config.iastWeakHashAlgorithms == ["MD2", "MD4"] as Set
    config.iastWeakCipherAlgorithms.pattern() == weekCypherAlgorithms
    !config.iastDeduplicationEnabled
    config.iastEnabled
    config.iastMaxConcurrentRequests == 5
    config.iastVulnerabilitiesPerRequest == 6
    config.iastRequestSampling == 50
  }

  def "check invalid algorithms pattern"() {
    setup:
    System.setProperty(PREFIX + IAST_WEAK_CIPHER_ALGORITHMS, "[*")

    when:
    def config = new Config()

    then:
    config.iastWeakCipherAlgorithms.pattern() == DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS
  }
}
