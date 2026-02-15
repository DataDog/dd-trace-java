package datadog.remoteconfig.state

import datadog.remoteconfig.PollingRateHinter
import datadog.remoteconfig.Product
import datadog.remoteconfig.ReportableException
import datadog.remoteconfig.tuf.RemoteConfigResponse
import spock.lang.Specification

class ProductStateSpecification extends Specification {

  PollingRateHinter hinter = Mock()

  void 'test apply with new and updated configs'() {
    given: 'a ProductState'
    def productState = new ProductState(Product.ASM_DATA)
    def listener = new OrderRecordingListener()
    productState.addProductListener(listener)

    and: 'first apply with config1 to cache it'
    def response1 = buildResponse([
      'org/ASM_DATA/config1/foo': [version: 1, length: 8, hash: 'oldhash1']
    ])
    def key1 = ParsedConfigKey.parse('org/ASM_DATA/config1/foo')
    productState.apply(response1, [key1], hinter)
    listener.operations.clear() // Clear for the actual test

    and: 'a new response with config1 (changed hash) and config2 (new)'
    def response2 = buildResponse([
      'org/ASM_DATA/config1/foo': [version: 2, length: 8, hash: 'newhash1'],
      'org/ASM_DATA/config2/foo': [version: 1, length: 8, hash: 'hash2']
    ])
    def key2 = ParsedConfigKey.parse('org/ASM_DATA/config2/foo')

    when: 'apply is called'
    def changed = productState.apply(response2, [key1, key2], hinter)

    then: 'changes are detected'
    changed

    and: 'operations happen in order: apply config1, apply config2, commit (no removes)'
    listener.operations == [
      'accept:org/ASM_DATA/config1/foo',
      'accept:org/ASM_DATA/config2/foo',
      'commit'
    ]
  }

  void 'test no changes detected when config hashes match'() {
    given: 'a ProductState'
    def productState = new ProductState(Product.ASM_DATA)
    def listener = new OrderRecordingListener()
    productState.addProductListener(listener)

    and: 'first apply with a config'
    def response = buildResponse([
      'org/ASM_DATA/config1/foo': [version: 1, length: 8, hash: 'hash1']
    ])
    def key1 = ParsedConfigKey.parse('org/ASM_DATA/config1/foo')
    productState.apply(response, [key1], hinter)
    listener.operations.clear() // Clear for the actual test

    when: 'apply is called again with the same hash'
    def changed = productState.apply(response, [key1], hinter)

    then: 'no changes are detected'
    !changed

    and: 'no listener operations occurred'
    listener.operations.isEmpty()
  }

  void 'test error handling during apply'() {
    given: 'a ProductState'
    def productState = new ProductState(Product.ASM_DATA)
    def listener = Mock(ProductListener)
    productState.addProductListener(listener)

    and: 'a response with a config'
    def response = buildResponse([
      'org/ASM_DATA/config1/foo': [version: 1, length: 8, hash: 'hash1']
    ])

    and: 'listener throws an exception'
    listener.accept(_, _, _) >> { throw new RuntimeException('Listener error') }

    def key1 = ParsedConfigKey.parse('org/ASM_DATA/config1/foo')

    when: 'apply is called'
    def changed = productState.apply(response, [key1], hinter)

    then: 'changes are still detected'
    changed

    and: 'commit is still called despite the error'
    1 * listener.commit(hinter)
  }

  void 'test reportable exception is recorded'() {
    given: 'a ProductState'
    def productState = new ProductState(Product.ASM_DATA)
    def listener = Mock(ProductListener)
    productState.addProductListener(listener)

    and: 'a response with a config'
    def response = buildResponse([
      'org/ASM_DATA/config1/foo': [version: 1, length: 8, hash: 'hash1']
    ])

    and: 'listener throws a ReportableException'
    def exception = new ReportableException('Test error')
    listener.accept(_, _, _) >> { throw exception }

    def key1 = ParsedConfigKey.parse('org/ASM_DATA/config1/foo')

    when: 'apply is called'
    productState.apply(response, [key1], hinter)

    then: 'error is recorded'
    productState.hasError()
    productState.getErrors().contains(exception)
  }

  void 'test configListeners are called in addition to productListeners'() {
    given: 'a ProductState'
    def productState = new ProductState(Product.ASM_DATA)
    def productListener = new OrderRecordingListener()
    def configListener = new OrderRecordingListener()
    productState.addProductListener(productListener)
    productState.addProductListener('config1', configListener)

    and: 'a response with two configs'
    def response = buildResponse([
      'org/ASM_DATA/config1/foo': [version: 1, length: 8, hash: 'hash1'],
      'org/ASM_DATA/config2/foo': [version: 1, length: 8, hash: 'hash2']
    ])

    def key1 = ParsedConfigKey.parse('org/ASM_DATA/config1/foo')
    def key2 = ParsedConfigKey.parse('org/ASM_DATA/config2/foo')

    when: 'apply is called'
    productState.apply(response, [key1, key2], hinter)

    then: 'productListener received both configs'
    productListener.operations.findAll { it.startsWith('accept:') }.size() == 2

    and: 'configListener only received config1'
    configListener.operations == ['accept:org/ASM_DATA/config1/foo', 'commit']
  }

  void 'test remove operations cleanup cached data'() {
    given: 'a ProductState'
    def productState = new ProductState(Product.ASM_DATA)
    def listener = Mock(ProductListener)
    productState.addProductListener(listener)

    and: 'first apply with a config to cache it'
    def response1 = buildResponse([
      'org/ASM_DATA/config1/foo': [version: 1, length: 8, hash: 'hash1']
    ])
    def key1 = ParsedConfigKey.parse('org/ASM_DATA/config1/foo')
    productState.apply(response1, [key1], hinter)

    and: 'an empty response (config should be removed)'
    def response2 = buildResponse([:])

    when: 'apply is called'
    def changed = productState.apply(response2, [], hinter)

    then: 'changes are detected'
    changed

    and: 'listener remove was called'
    1 * listener.remove(key1, hinter)

    and: 'cached data is cleaned up'
    productState.getCachedTargetFiles().isEmpty()
    productState.getConfigStates().isEmpty()
  }

  // Helper methods

  RemoteConfigResponse buildResponse(Map<String, Map> targets) {
    def response = Mock(RemoteConfigResponse)

    for (def entry : targets.entrySet()) {
      def path = entry.key
      def targetData = entry.value

      def target = new RemoteConfigResponse.Targets.ConfigTarget()
      def hashString = targetData.hash as String
      target.hashes = ['sha256': hashString]
      target.length = targetData.length as long

      def custom = new RemoteConfigResponse.Targets.ConfigTarget.ConfigTargetCustom()
      custom.version = targetData.version as long
      target.custom = custom

      response.getTarget(path) >> target
      response.getFileContents(path) >> "content_${targetData.hash}".bytes
    }

    // Handle empty targets case
    if (targets.isEmpty()) {
      response.getTarget(_) >> null
    }

    return response
  }

  // Test helper class to record operation order
  static class OrderRecordingListener implements ProductListener {
    List<String> operations = []

    @Override
    void accept(datadog.remoteconfig.state.ConfigKey configKey, byte[] content, PollingRateHinter pollingRateHinter) {
      operations << "accept:${configKey.toString()}"
    }

    @Override
    void remove(datadog.remoteconfig.state.ConfigKey configKey, PollingRateHinter pollingRateHinter) {
      operations << "remove:${configKey.toString()}"
    }

    @Override
    void commit(PollingRateHinter pollingRateHinter) {
      operations << 'commit'
    }
  }
}
