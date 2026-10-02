package com.datadog.profiling.otel;

import java.util.function.Consumer;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValue;
import org.openjdk.jmc.flightrecorder.writer.api.TypedValueBuilder;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

public final class JfrTools {
  /**
   * Helper method to write JFR events with automatic startTime field.
   *
   * <p>This ensures all events have the required startTime field set, which is necessary for the
   * JFR parser to correctly read subsequent field values.
   *
   * @param recording the JFR recording to write to
   * @param eventType the event type to create
   * @param fieldSetter consumer that sets additional event fields
   */
  public static void writeEvent(
      Recording recording, Type eventType, Consumer<TypedValueBuilder> fieldSetter) {
    recording.writeEvent(
        eventType.asValue(
            valueBuilder -> {
              valueBuilder.putField("startTime", System.nanoTime());
              fieldSetter.accept(valueBuilder);
            }));
  }

  /**
   * Helper method to build a JFR stack trace field value from StackTraceElement array.
   *
   * <p>Constructs the proper JFR stack trace structure: { frames: StackFrame[], truncated: boolean
   * } where each StackFrame contains: { method: { type: { name: String }, name: String },
   * lineNumber: int, bytecodeIndex: int, type: String }
   *
   * @param types the Types instance from the recording
   * @param stackTraceBuilder the builder to construct the stack trace value
   * @param stackTrace the stack trace elements to convert
   */
  public static void putStackTrace(
      Types types, TypedValueBuilder stackTraceBuilder, StackTraceElement[] stackTrace) {
    // Get the StackFrame type
    Type stackFrameType = types.getType(Types.JDK.STACK_FRAME);

    // Build array of stack frame TypedValues
    TypedValue[] frames = new TypedValue[stackTrace.length];
    for (int i = 0; i < stackTrace.length; i++) {
      StackTraceElement element = stackTrace[i];
      frames[i] =
          stackFrameType.asValue(
              frameBuilder -> {
                // Build method: { type: Class, name: String }
                frameBuilder.putField(
                    "method",
                    methodBuilder -> {
                      // Build type (Class): { name: String }
                      methodBuilder.putField(
                          "type",
                          classBuilder -> {
                            classBuilder.putField("name", element.getClassName());
                          });
                      methodBuilder.putField("name", element.getMethodName());
                    });
                frameBuilder.putField("lineNumber", element.getLineNumber());
                frameBuilder.putField("bytecodeIndex", -1);
                frameBuilder.putField("type", element.isNativeMethod() ? "Native" : "Java");
              });
    }

    // Set the frames array and truncated flag
    stackTraceBuilder.putField("frames", frames);
    stackTraceBuilder.putField("truncated", stackTrace.length > 8192);
  }
}
