package datadog.trace.instrumentation.tibcobw6;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.TIBCO_NODE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.TIBCO_VERSION;

import datadog.environment.SystemProperties;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TibcoDecorator extends BaseDecorator {
  private static final Logger LOGGER = LoggerFactory.getLogger(TibcoDecorator.class);
  private static final CharSequence TIBCO_BW = UTF8BytesString.create("tibco_bw");
  private static final CharSequence APPNODE_NAME =
      UTF8BytesString.create(SystemProperties.get("bw.appnode"));
  private static final CharSequence BW_VERSION = bwVersion();
  public static final CharSequence TIBCO_PROCESS_OPERATION =
      UTF8BytesString.create("tibco.process");
  public static final CharSequence TIBCO_ACTIVITY_OPERATION =
      UTF8BytesString.create("tibco.activity");
  public static final TibcoDecorator DECORATE = new TibcoDecorator();

  private static CharSequence bwVersion() {
    try {
      Class cls =
          Class.forName(
              "com.tibco.bw.thor.management.common.SetupUtils",
              false,
              ClassLoader.getSystemClassLoader());
      Map<String, String> map =
          (Map<String, String>) cls.getMethod("loadProductConfiguration").invoke(null);
      if (map != null) {
        return map.get("product.version");
      }
    } catch (Throwable t) {
      LOGGER.warn("Error while obtaining the Tibco BW version", t);
    }
    return null;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {TIBCO_BW.toString()};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.TIBCO_BW;
  }

  @Override
  protected CharSequence component() {
    return TIBCO_BW;
  }

  @Override
  public AgentSpan afterStart(final AgentSpan span) {
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_INTERNAL);
    return super.afterStart(span);
  }

  public AgentSpan onProcessStart(AgentSpan span, String processName) {
    return span.setResourceName(processName)
        .setTag(TIBCO_NODE, APPNODE_NAME)
        .setTag(TIBCO_VERSION, BW_VERSION)
        .setMeasured(true);
  }

  public AgentSpan onActivityStart(final AgentSpan span, String activityName) {
    span.setResourceName(activityName);
    return span;
  }
}
