package datadog.trace.civisibility.interceptor;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * An interceptor that removes spans and tags that are not supported in the APM protocol (some spans
 * and tags related to certain CI Visibility features, e.g. Test Suite Level Visibility, can only be
 * written with CI Test Cycle protocol). Also sets common CI Visibility tags (that are otherwise set
 * in intake message header when CI Test Cycle protocol is used).
 */
public class CiVisibilityApmProtocolInterceptor extends AbstractTraceInterceptor {

  public static final CiVisibilityApmProtocolInterceptor INSTANCE =
      new CiVisibilityApmProtocolInterceptor(Priority.CI_VISIBILITY_APM, Config.get());

  private final CiVisibilityWellKnownTags wellKnownTags;

  protected CiVisibilityApmProtocolInterceptor(Priority priority, Config config) {
    super(priority);
    wellKnownTags = config.getCiVisibilityWellKnownTags();
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {

    List<? extends MutableSpan> filteredTrace =
        trace.stream().filter(this::isSupportedByApmProtocol).collect(Collectors.toList());
    for (MutableSpan span : filteredTrace) {
      span.setTag(Tags.TEST_SESSION_ID, (Number) null);
      span.setTag(Tags.TEST_MODULE_ID, (Number) null);
      span.setTag(Tags.TEST_SUITE_ID, (Number) null);

      String spanType = span.getSpanType();
      if (DDSpanTypes.TEST.equals(spanType)) {
        span.setTag(Tags.RUNTIME_NAME, wellKnownTags.getRuntimeName().toString());
        span.setTag(Tags.RUNTIME_VENDOR, wellKnownTags.getRuntimeVendor().toString());
        span.setTag(Tags.RUNTIME_VERSION, wellKnownTags.getRuntimeVersion().toString());
        span.setTag(Tags.OS_ARCHITECTURE, wellKnownTags.getOsArch().toString());
        span.setTag(Tags.OS_PLATFORM, wellKnownTags.getOsPlatform().toString());
        span.setTag(Tags.OS_VERSION, wellKnownTags.getOsVersion().toString());
        span.setTag(
            DDTags.TEST_IS_USER_PROVIDED_SERVICE,
            wellKnownTags.getIsUserProvidedService().toString());
      }
    }
    return filteredTrace;
  }

  private boolean isSupportedByApmProtocol(MutableSpan span) {
    String spanType = span.getSpanType();
    return !DDSpanTypes.TEST_SESSION_END.equals(spanType)
        && !DDSpanTypes.TEST_MODULE_END.equals(spanType)
        && !DDSpanTypes.TEST_SUITE_END.equals(spanType);
  }
}
