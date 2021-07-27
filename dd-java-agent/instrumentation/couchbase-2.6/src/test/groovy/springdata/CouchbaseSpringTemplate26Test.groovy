package springdata

import datadog.trace.agent.test.asserts.TraceAssert

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class CouchbaseSpringTemplate26Test extends CouchbaseSpringTemplateTest {
  @Override
  void assertCouchbaseCall(TraceAssert trace, String name, String bucketName = null, Object parentSpan = null) {
    CouchbaseSpanUtil.assertCouchbaseCall(trace, name, bucketName, parentSpan)
  }
}
