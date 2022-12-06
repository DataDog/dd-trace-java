package com.datadog.profiling.controller.jfr;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import org.openjdk.jmc.flightrecorder.writer.RecordingImpl;
import org.openjdk.jmc.flightrecorder.writer.api.Annotation;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypeStructureBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.TypedFieldBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValueBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/** An 'extended' JFR recorder adding the ability to use the Java JFR API to define event types. */
public class TestJfrRecorder {
  static final class AnnotationValueObject {
    final Type annotationType;
    final String annotationValue;

    public AnnotationValueObject(Type annotationType, String value) {
      this.annotationType = annotationType;
      this.annotationValue = value;
    }
  }

  private final Map<StackTraceElement, TypedValue> frameCache = new HashMap<>(16000);
  private final Map<String, TypedValue> classLoaderCache = new HashMap<>(128);
  private final Map<String, TypedValue> moduleCache = new HashMap<>(4096);
  private final Recording recording;

  public TestJfrRecorder(Recording recording) {
    this.recording = recording;
  }

  public final Type registerEventType(Class<? extends Event> eventType) {
    Types types = recording.getTypes();
    /*
     * JMC implementation is slightly mishandling some event types - not using the special call
     * and rather registering all implicit fields by hand.
     */
    return recording.registerType(
        getEventName(eventType),
        "jdk.jfr.Event",
        b -> {
          Field[] fields = eventType.getDeclaredFields();
          for (Field f : fields) {
            if (Modifier.isTransient(f.getModifiers()) || Modifier.isStatic(f.getModifiers())) {
              // skip static and transient fields
              continue;
            }
            // Add field definition
            Type fieldType = types.getType(f.getType().getName());
            if (fieldType != null) {
              java.lang.annotation.Annotation[] as = f.getAnnotations();
              String fieldName = getFieldName(f);
              if (fieldName.equals("startTime")
                  || fieldName.equals("eventThread")
                  || fieldName.equals("stackTrace")) {
                // built-in fields; skip
                continue;
              }
              TypedFieldBuilder fieldTypeBuilder = types.fieldBuilder(fieldName, fieldType);

              for (java.lang.annotation.Annotation a : as) {
                AnnotationValueObject val = processAnnotation(types, a);
                if (val != null) {
                  fieldTypeBuilder =
                      val.annotationValue != null
                          ? fieldTypeBuilder.addAnnotation(val.annotationType, val.annotationValue)
                          : fieldTypeBuilder.addAnnotation(val.annotationType);
                }
              }
              b.addField(fieldTypeBuilder.build());
            }
          }
          // force 'startTime' field
          b.addField(
              "startTime",
              Types.Builtin.LONG,
              field ->
                  field.addAnnotation(Types.JDK.ANNOTATION_TIMESTAMP, "NANOSECONDS_SINCE_EPOCH"));
          // force 'eventThread' field
          b.addField("eventThread", Types.JDK.THREAD);

          // force 'stackTrace' field if the event is collecting stacktraces
          if (hasStackTrace(eventType)) {
            b.addField("stackTrace", Types.JDK.STACK_TRACE);
          }
          for (java.lang.annotation.Annotation a : eventType.getAnnotations()) {
            AnnotationValueObject val = processAnnotation(types, a);
            if (val != null) {
              b =
                  val.annotationValue != null
                      ? b.addAnnotation(val.annotationType, val.annotationValue)
                      : b.addAnnotation(val.annotationType);
            }
          }
        });
  }

  private AnnotationValueObject processAnnotation(
      Types types, java.lang.annotation.Annotation annotation) {
    // skip non-JFR related annotations
    if (!isJfrAnnotation(annotation)) {
      return null;
    }
    if (annotation instanceof Name) {
      // skip @Name annotation
      return null;
    }

    String value = null;
    try {
      Method m = annotation.getClass().getMethod("value");
      if (!String.class.isAssignableFrom(m.getReturnType())) {
        // wrong value type
        return null;
      }
      value = (String) m.invoke(annotation);
    } catch (NoSuchMethodException ignored) {
      // no-value annotations are also permitted
    } catch (SecurityException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException e) {
      // error retrieving value attribute
      return null;
    }
    String annotationValue = value;
    String annotationTypeName = annotation.annotationType().getTypeName();
    Type annotationType =
        types.getOrAdd(
            annotationTypeName,
            Annotation.ANNOTATION_SUPER_TYPE_NAME,
            builder -> {
              if (annotationValue != null) {
                builder.addField("value", Types.Builtin.STRING);
              }
            });
    return new AnnotationValueObject(annotationType, annotationValue);
  }

  public final TestJfrRecorder writeEvent(Event event) {
    registerEventType(event.getClass());
    recording.writeEvent(createEventValue(event));
    return this;
  }

  public RecordingImpl writeEvent(TypedValue event) {
    return recording.writeEvent(event);
  }

  public Type registerEventType(String name) {
    return recording.registerEventType(name);
  }

  public Type registerEventType(String name, Consumer<TypeStructureBuilder> builderCallback) {
    return recording.registerEventType(name, builderCallback);
  }

  public Type registerAnnotationType(String name) {
    return recording.registerAnnotationType(name);
  }

  public Type registerAnnotationType(String name, Consumer<TypeStructureBuilder> builderCallback) {
    return recording.registerAnnotationType(name, builderCallback);
  }

  public Type registerType(String name, Consumer<TypeStructureBuilder> builderCallback) {
    return recording.registerType(name, builderCallback);
  }

  public Type registerType(
      String name, String supertype, Consumer<TypeStructureBuilder> builderCallback) {
    return recording.registerType(name, supertype, builderCallback);
  }

  public Type getType(Types.JDK type) {
    return recording.getType(type);
  }

  public Type getType(String typeName) {
    return recording.getType(typeName);
  }

  public Types getTypes() {
    return recording.getTypes();
  }

  private TypedValue createEventValue(Event event) {
    Type eventType = recording.getType(getEventName(event.getClass()));
    Field[] fields = event.getClass().getDeclaredFields();

    TypedValue typedValue =
        eventType.asValue(
            access -> {
              boolean startTimeWritten = false;
              boolean eventThreadWritten = false;
              boolean stackTraceWritten = false;
              for (Field f : fields) {
                f.setAccessible(true);

                /*
                 * From jdk.jfr.Event.java: Supported field types are the Java primitives: {@code
                 * boolean}, {@code char}, {@code byte}, {@code short}, {@code int}, {@code long},
                 * {@code float}, and {@code double}. Supported reference types are: {@code String},
                 * {@code Thread} and {@code Class}. Arrays, enums, and other reference types are
                 * silently ignored and not included. Fields that are of the supported types can be
                 * excluded by using the transient modifier. Static fields, even of the supported
                 * types, are not included.
                 */
                // Transient and static fields are excluded
                if (Modifier.isTransient(f.getModifiers()) || Modifier.isStatic(f.getModifiers())) {
                  continue;
                }

                String fldName = getFieldName(f);
                if (fldName.equals("startTime")) {
                  startTimeWritten = true;
                } else if (fldName.equals("eventThread")) {
                  eventThreadWritten = true;
                } else if (fldName.equals("stackTrace")) {
                  stackTraceWritten = true;
                }
                try {
                  switch (f.getType().getName()) {
                    case "byte":
                      {
                        byte byteValue = f.getByte(event);
                        access.putField(fldName, byteValue);
                        break;
                      }
                    case "char":
                      {
                        char charValue = f.getChar(event);
                        access.putField(fldName, charValue);
                        break;
                      }
                    case "short":
                      {
                        short shortValue = f.getShort(event);
                        access.putField(fldName, shortValue);
                        break;
                      }
                    case "int":
                      {
                        int intValue = f.getInt(event);
                        access.putField(fldName, intValue);
                        break;
                      }
                    case "long":
                      {
                        long longValue = f.getLong(event);
                        access.putField(fldName, longValue);
                        break;
                      }
                    case "float":
                      {
                        float floatValue = f.getFloat(event);
                        access.putField(fldName, floatValue);
                        break;
                      }
                    case "double":
                      {
                        double doubleValue = f.getDouble(event);
                        access.putField(fldName, doubleValue);
                        break;
                      }
                    case "boolean":
                      {
                        boolean booleanValue = f.getBoolean(event);
                        access.putField(fldName, booleanValue);
                        break;
                      }
                    case "java.lang.String":
                      {
                        String stringValue = (String) f.get(event);
                        access.putField(fldName, stringValue);
                        break;
                      }
                    case "java.lang.Class":
                      {
                        Class<?> clz = (Class<?>) f.get(event);
                        access.putField(
                            fldName,
                            fldAccess -> {
                              fldAccess
                                  .putField(
                                      "name",
                                      nameAccess -> {
                                        nameAccess.putField("string", clz.getSimpleName());
                                      })
                                  .putField("package", clz.getPackage().getName())
                                  .putField("modifiers", clz.getModifiers());
                            });
                        break;
                      }
                    case "java.lang.Thread":
                      {
                        Thread thrd = (Thread) f.get(event);
                        putThreadField(access, fldName, thrd);
                        break;
                      }
                    case "java.lang.StackTraceElement[]":
                      {
                        StackTraceElement[] stackTrace = (StackTraceElement[]) f.get(event);
                        putStackTraceField(access, fldName, stackTrace);
                        break;
                      }
                    default:
                      {
                        // System.err.println("Cannot write type:" + f.getType().getName());
                      }
                  }
                } catch (IllegalAccessException e) {
                  throw new RuntimeException();
                }
              }
              if (!startTimeWritten) {
                // default to 0
                access.putField("startTime", 0L);
              }
              if (!eventThreadWritten) {
                // default to current thread
                putThreadField(access, "eventThread", Thread.currentThread());
              }
              if (!stackTraceWritten && hasStackTrace(event.getClass())) {
                putStackTraceField(access, "stackTrace", Thread.currentThread().getStackTrace());
              }
            });

    return typedValue;
  }

  private String getEventName(Class<? extends Event> eventType) {
    Name nameAnnotation = eventType.getAnnotation(Name.class);
    if (nameAnnotation != null) {
      return nameAnnotation.value();
    }
    return eventType.getSimpleName();
  }

  private String getFieldName(Field fld) {
    Name nameAnnotation = fld.getAnnotation(Name.class);
    if (nameAnnotation != null) {
      return nameAnnotation.value();
    }
    return fld.getName();
  }

  private boolean isJfrAnnotation(java.lang.annotation.Annotation target) {
    String typeName = target.annotationType().getName();
    if (typeName.startsWith("jdk.jfr.")) {
      return true;
    }
    for (java.lang.annotation.Annotation annotation : target.annotationType().getAnnotations()) {
      if (isJfrAnnotation(annotation)) {
        return true;
      }
    }
    return false;
  }

  private void putThreadField(TypedValueBuilder access, String fldName, Thread thread) {
    access.putField(
        fldName,
        fldAccess -> {
          fldAccess
              .putField("javaThreadId", thread.getId())
              .putField("osThreadId", thread.getId())
              .putField("javaName", thread.getName());
        });
  }

  private void putStackTraceField(
      TypedValueBuilder access, String fldName, StackTraceElement[] stackTrace) {
    Types types = access.getType().getTypes();
    TypedValue[] frames = new TypedValue[stackTrace.length];
    boolean[] truncated = new boolean[] {false};
    for (int i = 0; i < stackTrace.length; i++) {
      frames[i] = asStackFrame(types, stackTrace[i]);
      if (i >= 8192) {
        truncated[0] = true;
        break;
      }
    }
    access.putField(
        fldName,
        p -> {
          p.putField("frames", frames).putField("truncated", truncated[0]);
        });
  }

  private TypedValue asStackFrame(Types types, StackTraceElement element) {
    return frameCache.computeIfAbsent(
        element,
        k ->
            types
                .getType(Types.JDK.STACK_FRAME)
                .asValue(
                    p -> {
                      p.putField("method", methodValue(types, k))
                          .putField("lineNumber", k.getLineNumber())
                          .putField("bytecodeIndex", -1)
                          .putField("type", k.isNativeMethod() ? "native" : "java");
                    }));
  }

  private TypedValue methodValue(Types types, StackTraceElement element) {
    return types
        .getType(Types.JDK.METHOD)
        .asValue(
            p -> {
              p.putField("type", classValue(types, element))
                  .putField("name", element.getMethodName());
            });
  }

  private TypedValue classValue(Types types, StackTraceElement element) {
    return types
        .getType(Types.JDK.CLASS)
        .asValue(
            p -> {
              p.putField("name", getSimpleName(element.getClassName()));
            });
  }

  private TypedValue classLoaderValue(Types types, String classLoaderName) {
    return classLoaderCache.computeIfAbsent(
        classLoaderName,
        k ->
            types
                .getType(Types.JDK.CLASS_LOADER)
                .asValue(
                    p -> {
                      p.putField("name", k);
                    }));
  }

  private TypedValue packageValue(Types types, String packageName, String module) {
    return types
        .getType(Types.JDK.PACKAGE)
        .asValue(
            p -> {
              p.putField("name", packageName).putField("module", moduleValue(types, module));
            });
  }

  private TypedValue moduleValue(Types types, String module) {
    return moduleCache.computeIfAbsent(
        module,
        k ->
            types
                .getType(Types.JDK.MODULE)
                .asValue(
                    p -> {
                      p.putField("name", k);
                    }));
  }

  private String getSimpleName(String className) {
    return className.substring(className.lastIndexOf('.') + 1);
  }

  private String getPackageName(String className) {
    int idx = className.lastIndexOf('.');
    if (idx > -1) {
      return className.substring(0, idx);
    }
    return "";
  }

  private boolean hasStackTrace(Class<? extends Event> eventType) {
    StackTrace stAnnotation = eventType.getAnnotation(StackTrace.class);
    if (stAnnotation != null) {
      return stAnnotation.value();
    }
    return false;
  }
}
