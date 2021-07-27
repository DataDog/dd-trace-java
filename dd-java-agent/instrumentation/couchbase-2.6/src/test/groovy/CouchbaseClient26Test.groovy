import datadog.trace.agent.test.asserts.TraceAssert
import spock.lang.Retry

@Retry
@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class CouchbaseClient26Test extends CouchbaseClientTest {
  @Override
  void assertCouchbaseCall(TraceAssert trace, String name, String bucketName = null, Object parentSpan = null) {
    CouchbaseSpanUtil.assertCouchbaseCall(trace, name, bucketName, parentSpan)
  }
}
