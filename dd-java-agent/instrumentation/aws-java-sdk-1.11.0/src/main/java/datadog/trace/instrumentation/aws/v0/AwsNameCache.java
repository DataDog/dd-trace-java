package datadog.trace.instrumentation.aws.v0;

import com.amazonaws.Request;
import datadog.trace.api.Functions;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.cache.QualifiedClassNameCache;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.regex.Pattern;

public class AwsNameCache {

  private static final Pattern REQUEST_PATTERN = Pattern.compile("Request", Pattern.LITERAL);
  private static final Pattern AMAZON_PATTERN = Pattern.compile("Amazon", Pattern.LITERAL);
  private static final DDCache<String, CharSequence> CACHE =
      DDCaches.newFixedSizeCache(128); // cloud services can have high cardinality

  private static final QualifiedClassNameCache CLASS_NAME_CACHE =
      new QualifiedClassNameCache(
          input -> REQUEST_PATTERN.matcher(input.getSimpleName()).replaceAll(""),
          Functions.SuffixJoin.of(
              ".",
              serviceName ->
                  AMAZON_PATTERN.matcher(String.valueOf(serviceName)).replaceAll("").trim()));

  public static CharSequence spanName(final Request<?> awsRequest) {
    return CACHE.computeIfAbsent(
        getQualifiedName(awsRequest).toString(),
        key ->
            UTF8BytesString.create(
                SpanNaming.instance()
                    .namingSchema()
                    .cloud()
                    .operationForRequest(
                        "aws",
                        AMAZON_PATTERN.matcher(awsRequest.getServiceName()).replaceAll("").trim(),
                        key)));
  }

  public static CharSequence getQualifiedName(final Request<?> awsRequest) {
    return CLASS_NAME_CACHE.getQualifiedName(
        awsRequest.getOriginalRequest().getClass(), awsRequest.getServiceName());
  }
}
