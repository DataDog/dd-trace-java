package com.datadog.profiling.controller.openjdk.events;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import datadog.trace.bootstrap.instrumentation.jfr.JfrHelper;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import jdk.jfr.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmapEntryFactory {

  private static final Logger log = LoggerFactory.getLogger(SmapEntryFactory.class);

  private static final AtomicBoolean REGISTERED = new AtomicBoolean();

  private static final EventType SMAP_ENTRY_EVENT_TYPE;
  private static final EventType AGGREGATED_SMAP_ENTRY_EVENT_TYPE;

  private static final SmapEntryCache SMAP_ENTRY_CACHE = new SmapEntryCache(Duration.ofMillis(500));

  static {
    if (!JavaVirtualMachine.isJ9() && !JavaVirtualMachine.isOracleJDK8()) {
      SMAP_ENTRY_EVENT_TYPE = EventType.getEventType(SmapEntryEvent.class);
      AGGREGATED_SMAP_ENTRY_EVENT_TYPE = EventType.getEventType(AggregatedSmapEntryEvent.class);
    } else {
      SMAP_ENTRY_EVENT_TYPE = null;
      AGGREGATED_SMAP_ENTRY_EVENT_TYPE = null;
    }
  }

  public static void registerEvents() {
    if (SMAP_ENTRY_EVENT_TYPE == null || AGGREGATED_SMAP_ENTRY_EVENT_TYPE == null) {
      // JFR is not available
      return;
    }

    // Make sure the periodic event is registered only once
    if (REGISTERED.compareAndSet(false, true) && OperatingSystem.isLinux()) {
      try {
        ObjectName objectName = new ObjectName("com.sun.management:type=DiagnosticCommand");
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        boolean annotatedMapsAvailable =
            Arrays.stream(mbs.getMBeanInfo(objectName).getOperations())
                .anyMatch(x -> x.getName().equals("systemMap"));
        if (annotatedMapsAvailable) {
          // Let's register the periodic SmapEntry event.
          // The AggregatedSmapEntry event will be generated from the common logic, based on the
          // peridicity settings of the SmapEntry event.
          JfrHelper.addPeriodicEvent(SmapEntryEvent.class, SmapEntryFactory::emitSingleEvents);
          JfrHelper.addPeriodicEvent(
              AggregatedSmapEntryEvent.class, SmapEntryFactory::emitAggregatedEvents);
          log.debug("Smap entry events registered successfully");
        }
      } catch (Exception e) {
        log.debug(
            SEND_TELEMETRY,
            "Smap entry events could not be registered due to missing systemMap operation");
      }
    }
  }

  public static void emitSingleEvents() {
    emitEvents(true, false);
  }

  public static void emitAggregatedEvents() {
    emitEvents(false, true);
  }

  public static void emitEvents(boolean singleEvents, boolean aggregatedEvents) {
    if (SMAP_ENTRY_EVENT_TYPE.isEnabled() || AGGREGATED_SMAP_ENTRY_EVENT_TYPE.isEnabled()) {
      // first collect the smap entries - this data structure is shared between the two events
      List<SmapEntryEvent> events = SMAP_ENTRY_CACHE.getEvents();
      if (singleEvents && SMAP_ENTRY_EVENT_TYPE.isEnabled()) {
        // emit the smap entry events
        SmapEntryEvent.emit(events);
      }
      if (aggregatedEvents && AGGREGATED_SMAP_ENTRY_EVENT_TYPE.isEnabled()) {
        // emit the aggregated smap entry events
        AggregatedSmapEntryEvent.emit(events);
      }
    }
  }
}
