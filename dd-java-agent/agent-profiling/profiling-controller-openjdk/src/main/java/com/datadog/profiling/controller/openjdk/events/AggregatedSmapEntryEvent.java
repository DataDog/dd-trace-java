package com.datadog.profiling.controller.openjdk.events;

import java.util.HashMap;
import java.util.List;
import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Period;
import jdk.jfr.StackTrace;

@Name("datadog.AggregatedSmapEntry")
@Label("Aggregated Smap Entry")
@Description("Entry for the entries from the smaps file aggregated by NMT category")
@Category("Datadog")
@Period("beginChunk")
@StackTrace(false)
public class AggregatedSmapEntryEvent extends Event {
  @Label("NMT Category")
  private final String nmtCategory;

  @Label("Resident Set Size")
  @DataAmount
  private final long rss;

  public AggregatedSmapEntryEvent(String nmtCategory, long rss) {
    this.nmtCategory = nmtCategory;
    this.rss = rss;
  }

  public static void emit() {
    if (EventType.getEventType(AggregatedSmapEntryEvent.class).isEnabled()) {
      HashMap<String, Long> aggregatedSmapEntries = new HashMap<>();
      List<? extends Event> collectedEvents = SmapEntryFactory.collectEvents();
      // A single entry should only be expected for the error cases
      if (collectedEvents.size() > 1) {
        collectedEvents.forEach(
            entry -> {
              aggregatedSmapEntries.merge(
                  ((SmapEntryEvent) entry).getNmtCategory(),
                  ((SmapEntryEvent) entry).getRss(),
                  Long::sum);
            });
        aggregatedSmapEntries.forEach(
            (nmtCategory, rss) -> {
              new AggregatedSmapEntryEvent(nmtCategory, rss).commit();
            });
      } else {
        collectedEvents.forEach(Event::commit);
      }
    }
  }
}
