package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.UserEventTrackingMode.DISABLED;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.api.internal.TraceSegment;
import java.util.Map;
import javax.annotation.Nonnull;

public class EventDecorator {

  public void onEvent(@Nonnull TraceSegment segment, String eventName, Map<String, String> tags) {
    segment.setTagTop("appsec.events." + eventName + ".track", true, true);
    segment.setTagTop(DDTags.MANUAL_KEEP, true);

    // Report user event tracking mode ("safe" or "extended")
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode != DISABLED) {
      segment.setTagTop("_dd.appsec.events." + eventName + ".auto.mode", mode.toString());
    }

    if (tags != null && !tags.isEmpty()) {
      segment.setTagTop("appsec.events." + eventName, tags);
    }
  }
}
