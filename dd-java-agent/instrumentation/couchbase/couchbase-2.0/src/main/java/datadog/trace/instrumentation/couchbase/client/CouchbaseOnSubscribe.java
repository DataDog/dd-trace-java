package datadog.trace.instrumentation.couchbase.client;

import static datadog.trace.instrumentation.couchbase.client.CouchbaseClientDecorator.DECORATE;

import datadog.trace.api.Functions;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.api.function.Function;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.rxjava.TracedOnSubscribe;
import java.lang.reflect.Method;
import rx.Observable;

public class CouchbaseOnSubscribe extends TracedOnSubscribe {

  private static final QualifiedClassNameCache NAMES =
      new QualifiedClassNameCache(
          new Function<Class<?>, CharSequence>() {
            @Override
            public String apply(Class<?> input) {
              StringBuilder builder = new StringBuilder(input.getSimpleName());
              int i;
              while ((i = builder.indexOf("CouchbaseAsync")) != -1)
                builder.delete(i, i + "CouchbaseAsync".length());
              while ((i = builder.indexOf("DefaultAsync")) != -1)
                builder.delete(i, i + "DefaultAsync".length());
              return builder.toString();
            }
          },
          Functions.PrefixJoin.of("."));

  private final String resourceName;
  private final String bucket;

  public CouchbaseOnSubscribe(
      final Observable originalObservable, final Method method, final String bucket) {
    super(originalObservable, "couchbase.call", DECORATE);
    resourceName = NAMES.getQualifiedName(method.getDeclaringClass(), method.getName()).toString();
    this.bucket = bucket;
  }

  @Override
  protected void afterStart(final AgentSpan span) {
    super.afterStart(span);

    span.setResourceName(resourceName);

    if (bucket != null) {
      span.setTag("bucket", bucket);
    }
  }
}
