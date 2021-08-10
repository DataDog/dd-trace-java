package springdata


import datadog.trace.agent.test.checkpoints.CheckpointValidator
import datadog.trace.agent.test.checkpoints.CheckpointValidationMode
import datadog.trace.agent.test.asserts.TraceAssert

class CouchbaseSpringRepository26Test extends CouchbaseSpringRepositoryTest {
  @Override
  void assertCouchbaseCall(TraceAssert trace, String name, String bucketName = null, Object parentSpan = null) {
    CouchbaseSpanUtil.assertCouchbaseCall(trace, name, bucketName, parentSpan)
  }

  @Override
  def setup() {
    CheckpointValidator.excludeValidations_DONOTUSE_I_REPEAT_DO_NOT_USE(
      CheckpointValidationMode.INTERVALS,
      CheckpointValidationMode.SUSPEND_RESUME,
      CheckpointValidationMode.THREAD_SEQUENCE)
  }
}
