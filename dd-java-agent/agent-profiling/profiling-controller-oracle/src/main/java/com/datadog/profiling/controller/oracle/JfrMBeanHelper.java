package com.datadog.profiling.controller.oracle;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;
import javax.management.*;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JfrMBeanHelper {

  private static final Logger log = LoggerFactory.getLogger(JfrMBeanHelper.class);

  private static final Pattern WHITESPACE_SPLITTER = Pattern.compile("\\s+");
  private static final Pattern HASHTAG_SPLITTER = Pattern.compile("#", Pattern.LITERAL);
  private static final String MC_BEAN_CLASS = "com.sun.management.MissionControl";
  private static final ObjectName MC_BEAN_NAME =
      getObjectName("com.sun.management:type=MissionControl");
  private static final ObjectName JFR_MBEAN_OBJECT_NAME =
      getObjectName("com.oracle.jrockit:type=FlightRecorder");

  // various MBean operations
  private static final String OPERATION_REGISTER_MBEANS = "registerMBeans";
  private static final String OPEN_STREAM = "openStream";
  private static final String READ_STREAM = "readStream";
  private static final String CLOSE_STREAM = "closeStream";
  public static final String CLOSE = "close";
  public static final String STOP = "stop";
  public static final String CLONE_RECORDING = "cloneRecording";

  // various MBean attributes
  private static final String DATA_END_TIME = "DataEndTime";

  // event settings attribute names
  private static final String KEY_ID = "id";
  private static final String KEY_THRESHOLD = "threshold";
  private static final String KEY_STACKTRACE_SERVER = "stacktrace";
  private static final String KEY_PERIOD_SERVER = "requestPeriod";
  private static final String KEY_ENABLED = "enabled";

  // recording settings attribute names
  private static final String KEY_NAME = "name";
  private static final String KEY_DURATION = "duration";
  private static final String KEY_DESTINATION_FILE = "destinationFile";
  private static final String KEY_DESTINATION_COMPRESSED = "destinationCompressed";
  private static final String KEY_START_TIME = "startTime";
  private static final String KEY_MAX_SIZE = "maxSize";
  private static final String KEY_MAX_AGE = "maxAge";
  private static final String KEY_TO_DISK = "toDisk";

  private static final String[] SETTING_NAMES =
      new String[] {
        KEY_NAME,
        KEY_TO_DISK,
        KEY_DURATION,
        KEY_MAX_SIZE,
        KEY_MAX_AGE,
        KEY_DESTINATION_FILE,
        KEY_START_TIME,
        KEY_DESTINATION_COMPRESSED
      };
  private static final OpenType<?>[] SETTING_TYPES =
      new OpenType[] {
        SimpleType.STRING,
        SimpleType.BOOLEAN,
        SimpleType.LONG,
        SimpleType.LONG,
        SimpleType.LONG,
        SimpleType.STRING,
        SimpleType.DATE,
        SimpleType.BOOLEAN
      };

  private static final String[] OPTION_NAMES =
      new String[] {KEY_ID, KEY_THRESHOLD, KEY_STACKTRACE_SERVER, KEY_PERIOD_SERVER, KEY_ENABLED};
  private static final OpenType<?>[] OPTION_TYPES =
      new OpenType[] {
        SimpleType.INTEGER, SimpleType.LONG, SimpleType.BOOLEAN, SimpleType.LONG, SimpleType.BOOLEAN
      };
  private static final CompositeType OPTIONS_COMPOSITE_TYPE = generateOptionsType();
  private static final CompositeType SETTINGS_COMPOSITE_TYPE = generateSettingsType();

  private static final AtomicInteger initPhase =
      new AtomicInteger(0); // 0 - not initialized, 1 - initializing, 2 - initialized

  private final MBeanServer server;

  private static void initialize() throws IOException {
    log.debug("Initializing MBean helper");
    if (initPhase.compareAndSet(0, 1)) {
      registerMBeans();

      initPhase.set(2);
    } else {
      // if the initialization is in progress (1) just do busy wait for it to finish (2)
      while (initPhase.get() != 2) {
        LockSupport.parkNanos(5_000L);
      }
    }
  }

  private static void registerMBeans() throws IOException {
    log.debug("Registering JFR MBeans");
    MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    try {
      try {
        server.createMBean(MC_BEAN_CLASS, MC_BEAN_NAME);
        log.debug("MissionControl MBean created");
      } catch (InstanceAlreadyExistsException iaee) {
        // Ok, it already exists.
      } catch (MBeanException mbe) {
        // This catch is a workaround for https://github.com/javaee/glassfish/issues/20686
        if (!(mbe.getTargetException() instanceof InstanceAlreadyExistsException)) {
          throw mbe;
        }
      }
      server.invoke(MC_BEAN_NAME, OPERATION_REGISTER_MBEANS, new Object[0], new String[0]);
      log.debug("JFR MBeans have been registered");
    } catch (JMException e) {
      throw new IOException(e);
    }
  }

  JfrMBeanHelper() throws IOException {
    initialize();

    if (JFR_MBEAN_OBJECT_NAME == null || MC_BEAN_NAME == null) {
      throw new IOException("JFR is not available");
    }
    this.server = ManagementFactory.getPlatformMBeanServer();
  }

  public Object getRecordingAttribute(ObjectName recording, String attribute) throws IOException {
    try {
      return server.getAttribute(recording, attribute);
    } catch (RuntimeOperationsException
        | MBeanException
        | AttributeNotFoundException
        | InstanceNotFoundException
        | ReflectionException e) {
      throw new IOException(e);
    }
  }

  public Object getJfrAttribute(String attribute) throws IOException {
    try {
      return server.getAttribute(JFR_MBEAN_OBJECT_NAME, attribute);
    } catch (RuntimeOperationsException
        | MBeanException
        | AttributeNotFoundException
        | InstanceNotFoundException
        | ReflectionException e) {
      throw new IOException(e);
    }
  }

  public ObjectName newRecording(
      String name, long maxSize, Duration maxAge, Map<String, String> eventSettings)
      throws IOException {
    log.debug("Creating a new recording {} with maxSize={} and maxAge={}", name, maxSize, maxAge);
    ObjectName recordingId = (ObjectName) invokeJfrOperation("createRecording", name);
    invokeJfrOperation(
        "setRecordingOptions", recordingId, encodeRecordingSettings(name, maxSize, maxAge));
    invokeJfrOperation("updateEventSettings", recordingId, encodeEventSettings(eventSettings));

    invokeJfrOperation("start", recordingId);

    // make sure the recording has started
    while (!(boolean) getRecordingAttribute(recordingId, "Running")) {
      LockSupport.parkNanos(100L); // 100ns step
    }
    log.debug("Recording {} has been created", name);
    return recordingId;
  }

  public long openStream(ObjectName recordingId, Date start, Date end) throws IOException {
    if ((boolean) getRecordingAttribute(recordingId, "Running")) {
      throw new IOException(
          "Can not open a data stream for active recording " + recordingId.getKeyProperty("name"));
    }
    if (start == null) {
      start = (Date) getRecordingAttribute(recordingId, "DataStartTime");
    }
    if (end == null) {
      end = (Date) getRecordingAttribute(recordingId, "DataEndTime");
    }
    log.debug(
        "Opening stream for recording {} and time range: {} - {}",
        recordingId.getKeyProperty("name"),
        start,
        end);
    return (long) invokeJfrOperation(OPEN_STREAM, recordingId, start, end);
  }

  public void closeStream(long streamId) throws IOException {
    log.debug("Closing stream {}", streamId);
    invokeJfrOperation(CLOSE_STREAM, streamId);
  }

  public byte[] readStream(long streamId) throws IOException {
    log.debug("Reading data from stream {}", streamId);
    byte[] data = (byte[]) invokeJfrOperation(READ_STREAM, streamId);
    log.debug("Read next {} bytes from stream {}", data == null ? -1 : data.length, streamId);
    return data;
  }

  public void stopRecording(ObjectName recordingId) throws IOException {
    log.debug("Stopping recording {}", recordingId.getKeyProperty("name"));
    invokeJfrOperation(STOP, recordingId);
    log.debug("Recording {} has been stopped", recordingId.getKeyProperty("name"));
  }

  public void closeRecording(ObjectName recordingId) throws IOException {
    log.debug("Closing recording {}", recordingId.getKeyProperty("name"));
    invokeJfrOperation(CLOSE, recordingId);
    log.debug("Recording {} has been closed", recordingId.getKeyProperty("name"));
  }

  public ObjectName cloneRecording(ObjectName recordingId) throws IOException {
    log.debug("Cloning recording {}", recordingId.getKeyProperty("name"));
    ObjectName cloned =
        (ObjectName)
            invokeJfrOperation(
                CLONE_RECORDING,
                recordingId,
                "Clone of " + recordingId.getKeyProperty("name"),
                Boolean.TRUE);
    log.debug(
        "Recording {} has been cloned to {}",
        recordingId.getKeyProperty("name"),
        cloned.getKeyProperty("name"));
    return cloned;
  }

  public Instant getDataEndTime(ObjectName recordingId) throws IOException {
    log.debug(
        "Retrieving DataEndTime attribute from recording {}", recordingId.getKeyProperty("name"));
    try {
      Date endTime = (Date) server.getAttribute(recordingId, DATA_END_TIME);
      return Instant.ofEpochMilli(endTime.getTime());
    } catch (JMException e) {
      throw new IOException(e);
    }
  }

  private Object invokeJfrOperation(String name, Object... parameters) throws IOException {
    try {
      return invokeJfrOperation(JFR_MBEAN_OBJECT_NAME, name, parameters);
    } catch (Throwable t) {
      throw new IOException(t);
    }
  }

  private Object invokeJfrOperation(ObjectName on, String operation, Object... parameters)
      throws JMException {
    return server.invoke(on, operation, parameters, extractSignature(parameters));
  }

  List<CompositeData> encodeEventSettings(Map<String, String> settings) throws IOException {
    try {
      List<CompositeData> eventSettings = new ArrayList<>();
      Map<String, Integer> typeIdMap = getEventIdKeyMap();
      Map<Integer, Map<String, String>> eventSettingsMap = new HashMap<>();
      for (Map.Entry<String, String> entry : settings.entrySet()) {
        String[] nameAttr = HASHTAG_SPLITTER.split(entry.getKey());
        String eventTypeName = JdkTypeIDs_Old.translateTo(nameAttr[0]);
        Integer typeId = typeIdMap.get(eventTypeName);
        if (typeId != null) {
          eventSettingsMap
              .computeIfAbsent(typeId, k -> new HashMap<>())
              .put(nameAttr[1], entry.getValue());
        }
      }
      for (Map.Entry<Integer, Map<String, String>> entry : eventSettingsMap.entrySet()) {
        Object[] values = new Object[] {entry.getKey(), -1L, Boolean.FALSE, -1L, Boolean.FALSE};
        for (Map.Entry<String, String> valueEntry : entry.getValue().entrySet()) {
          switch (valueEntry.getKey()) {
            case "threshold":
              {
                values[1] = parseDuration(valueEntry.getValue(), ChronoUnit.NANOS).toNanos();
                break;
              }
            case "stackTrace":
              {
                values[2] = Boolean.parseBoolean(valueEntry.getValue());
                break;
              }
            case "period":
              {
                String valueStr = valueEntry.getValue();
                if (valueStr.contains("Chunk")) {
                  values[3] = 0L; // magic number for 'everyChunk'
                } else {
                  values[3] = parseDuration(valueEntry.getValue(), ChronoUnit.MILLIS).toMillis();
                }
                break;
              }
            case "enabled":
              {
                values[4] = Boolean.parseBoolean(valueEntry.getValue());
                break;
              }
            default:
              {
                log.warn("Unsupported setting name: {}. Skipping.", valueEntry.getKey());
              }
          }
        }
        eventSettings.add(new CompositeDataSupport(OPTIONS_COMPOSITE_TYPE, OPTION_NAMES, values));
      }
      return eventSettings;
    } catch (OpenDataException e) {
      throw new IOException(e);
    }
  }

  CompositeData encodeRecordingSettings(String name, long maxSize, Duration maxAge)
      throws IOException {
    try {
      return new CompositeDataSupport(
          SETTINGS_COMPOSITE_TYPE,
          SETTING_NAMES,
          new Object[] {name, false, 0L, maxSize, maxAge.toMillis(), null, new Date(), false});
    } catch (OpenDataException e) {
      throw new IOException(e);
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Integer> getEventIdKeyMap() throws IOException {
    Map<String, Integer> map = new HashMap<>();
    List<CompositeData> compositeList = (List<CompositeData>) getJfrAttribute("EventDescriptors");

    for (CompositeData data : compositeList) {
      String uri = (String) data.get("uri");
      Integer id = (Integer) data.get("id");
      map.put(uri, id);
    }
    return map;
  }

  static Duration parseDuration(String timeUnitStr) {
    return parseDuration(timeUnitStr, ChronoUnit.NANOS);
  }

  static Duration parseDuration(String timeUnitStr, TemporalUnit defaultTimeUnit) {
    String[] valueUnit = WHITESPACE_SPLITTER.split(timeUnitStr);
    long value = Long.parseLong(valueUnit[0]);
    if (valueUnit.length == 1) {
      return Duration.of(value, defaultTimeUnit);
    }

    switch (valueUnit[1].toLowerCase(Locale.ROOT)) {
      case "ns":
        {
          return Duration.of(value, ChronoUnit.NANOS);
        }
      case "us":
        {
          return Duration.of(value, ChronoUnit.MICROS);
        }
      case "ms":
        {
          return Duration.of(value, ChronoUnit.MILLIS);
        }
      case "s":
        {
          return Duration.of(value, ChronoUnit.SECONDS);
        }
      case "m":
        {
          return Duration.of(value, ChronoUnit.MINUTES);
        }
      default:
        {
          log.debug(
              "Unsupported time unit: {}. Assuming {}", valueUnit[1], defaultTimeUnit.toString());
          return Duration.of(value, defaultTimeUnit);
        }
    }
  }

  private static CompositeType generateOptionsType() {
    try {
      return new CompositeType(
          "EventOptions", "Event Options", OPTION_NAMES, OPTION_NAMES, OPTION_TYPES);
    } catch (Exception e) {
      // Will not ever happen!
    }
    throw new RuntimeException();
  }

  private static CompositeType generateSettingsType() {
    try {
      return new CompositeType(
          "RecordingOptions", "RecordingOptions", SETTING_NAMES, SETTING_NAMES, SETTING_TYPES);
    } catch (Exception e) {
      // Will not ever happen!
    }
    throw new RuntimeException();
  }

  /**
   * Automatically generates the signature to be used when invoking operations.
   *
   * @param param the parameters for which to get the signature.
   * @return the signature matching the parameters.
   */
  private static String[] extractSignature(Object[] param) {
    String[] sig = new String[param.length];
    for (int i = 0; i < sig.length; i++) {
      if (param[i].getClass() == Boolean.class) {
        sig[i] = Boolean.TYPE.getName();
      } else if (Number.class.isAssignableFrom(param[i].getClass())) {
        try {
          sig[i] = ((Class<?>) param[i].getClass().getField("TYPE").get(param[i])).getName();
        } catch (IllegalArgumentException
            | SecurityException
            | IllegalAccessException
            | NoSuchFieldException e) {
          throw new UndeclaredThrowableException(e);
        }
      } else if (CompositeData.class.isAssignableFrom(param[i].getClass())) {
        sig[i] = CompositeData.class.getName();
      } else if (TabularData.class.isAssignableFrom(param[i].getClass())) {
        sig[i] = TabularData.class.getName();
      } else if (List.class.isAssignableFrom(param[i].getClass())) {
        sig[i] = List.class.getName();
      } else {
        sig[i] = param[i].getClass().getName();
      }
    }
    return sig;
  }

  private static ObjectName getObjectName(String objectName) {
    try {
      return ObjectName.getInstance(objectName);
    } catch (MalformedObjectNameException e) {
      log.debug("Invalid object name: {}", objectName);
    }
    return null;
  }
}
