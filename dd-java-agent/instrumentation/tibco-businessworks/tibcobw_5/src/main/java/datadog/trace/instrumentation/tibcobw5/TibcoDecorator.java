package datadog.trace.instrumentation.tibcobw5;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.TIBCO_NODE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.TIBCO_VERSION;

import com.tibco.pe.PEVersion;
import com.tibco.pe.core.JobPool;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TibcoDecorator extends BaseDecorator {
  private static final CharSequence TIBCO_BW = UTF8BytesString.create("tibco_bw");
  private static final Logger LOGGER = LoggerFactory.getLogger(TibcoDecorator.class);
  public static final CharSequence TIBCO_PROCESS_OPERATION =
      UTF8BytesString.create("tibco.process");
  public static final CharSequence TIBCO_ACTIVITY_OPERATION =
      UTF8BytesString.create("tibco.activity");
  public static final TibcoDecorator DECORATE = new TibcoDecorator();
  private static final CharSequence VERSION = UTF8BytesString.create(extractVersion());

  private static String extractVersion() {
    String v = PEVersion.getVersion();
    if (v == null) {
      return null;
    }
    // it's something like version 15.0, build xx, some date
    Pattern pattern = Pattern.compile("\\D*(\\d[^,]*).*");
    Matcher matcher = pattern.matcher(v);
    if (matcher.matches()) {
      return matcher.group(1);
    }
    LOGGER.debug(
        "Unable to extract the tibco businessworks version. The tag `tibco.version` will be missing from process spans");
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
        .setTag(TIBCO_NODE, JobPool.getName())
        .setTag(TIBCO_VERSION, VERSION)
        .setMeasured(true);
  }

  public AgentSpan onActivityStart(final AgentSpan span, String activityName) {
    span.setResourceName(activityName);
    return span;
  }
}
