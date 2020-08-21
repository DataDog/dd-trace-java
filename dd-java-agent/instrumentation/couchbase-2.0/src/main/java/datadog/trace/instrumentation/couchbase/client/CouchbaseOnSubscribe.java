package datadog.trace.instrumentation.couchbase.client;

import static datadog.trace.instrumentation.couchbase.client.CouchbaseClientDecorator.DECORATE;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.rxjava.TracedOnSubscribe;
import rx.Observable;

public class CouchbaseOnSubscribe extends TracedOnSubscribe {
  private final String resourceName;
  private final String bucket;

  public CouchbaseOnSubscribe(
      final Observable originalObservable,
      final Class<?> originType,
      final String originMethod,
      final String bucket) {
    super(originalObservable, "couchbase.call", DECORATE);

    final String className =
        originType.getSimpleName().replace("CouchbaseAsync", "").replace("DefaultAsync", "");
    resourceName = className + "." + originMethod;
    this.bucket = bucket;
  }

  @Override
  protected void afterStart(final AgentSpan span) {
    super.afterStart(span);

    span.setTag(DDTags.RESOURCE_NAME, resourceName);

    if (bucket != null) {
      span.setTag("bucket", bucket);
    }
  }
}
