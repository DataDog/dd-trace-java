package springdata


import datadog.trace.agent.test.asserts.TraceAssert

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class CouchbaseSpringRepository26Test extends CouchbaseSpringRepositoryTest {
  @Override
  void assertCouchbaseCall(TraceAssert trace, String name, String bucketName = null, Object parentSpan = null) {
    CouchbaseSpanUtil.assertCouchbaseCall(trace, name, bucketName, parentSpan)
  }
}
