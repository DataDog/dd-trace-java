package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadog.debugger.sink.Snapshot;
import datadog.trace.bootstrap.debugger.CapturedContext;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import utils.SourceCompiler;

class CaptureAssertionHelper {
  private static final CapturedContext.CapturedThrowable HANDLED_EXCEPTION =
      new CapturedContext.CapturedThrowable(new IllegalArgumentException());
  private static final CapturedContext.CapturedThrowable UNHANDLED_EXCEPTION =
      new CapturedContext.CapturedThrowable(new RuntimeException());

  private final Map<DebuggerTransformerTest.ExceptionKind, CapturedContext.CapturedValue[]>
      enterArgs = new HashMap<>();
  private final Map<DebuggerTransformerTest.ExceptionKind, CapturedContext.CapturedValue[]>
      returnArgs = new HashMap<>();
  private final Map<DebuggerTransformerTest.ExceptionKind, CapturedContext.CapturedValue>
      returnValues = new HashMap<>();
  private CapturedContext.CapturedValue[] beforeLocals = null;
  private CapturedContext.CapturedValue[] afterLocals = null;
  private CapturedContext.CapturedValue[] returnLocals = null;

  private final DebuggerTransformerTest.InstrumentationKind instrumentationKind;
  private final SourceCompiler.DebugInfo debugInfo;
  private final String argumentType;
  private final Object argumentInputValue;
  private final Object argumentOutputValue;
  private final String returnType;
  private final Object returnValue;
  private final String[] expectedArgNames;
  private final int expectedLineFrom;
  private final int expectedLineTill;
  private final CapturedContext.CapturedValue[] correlationFields;
  private final List<Snapshot> snapshots;

  CaptureAssertionHelper(
      DebuggerTransformerTest.InstrumentationKind instrumentationKind,
      SourceCompiler.DebugInfo debugInfo,
      String argumentType,
      Object argumentInputValue,
      Object argumentOutputValue,
      String returnType,
      Object returnValue,
      int expectedLineFrom,
      int expectedLineTill,
      CapturedContext.CapturedValue[] correlationFields,
      List<Snapshot> snapshots) {
    this.instrumentationKind = instrumentationKind;
    this.debugInfo = debugInfo;
    this.argumentType = argumentType;
    this.argumentInputValue = argumentInputValue;
    this.argumentOutputValue = argumentOutputValue;
    this.returnType = returnType;
    this.returnValue = returnValue;
    this.expectedLineFrom = expectedLineFrom;
    this.expectedLineTill = expectedLineTill;
    this.correlationFields = correlationFields;
    this.snapshots = snapshots;

    this.expectedArgNames =
        hasVariableInfo(debugInfo) ? new String[] {"arg1", "switcher"} : new String[] {"p0", "p1"};

    setupArguments();
    setupLocals(instrumentationKind);
    setupReturnValues();
  }

  void assertCaptures(DebuggerTransformerTest.ExceptionKind exceptionKind) {
    // Map<Snapshot.Capture.Kind, Set<Snapshot.Capture>> captureMap = getCaptureMap(exceptionKind);
    Snapshot.Captures captures = snapshots.get(exceptionKind.ordinal()).getCaptures();
    assertEntryExit(exceptionKind, captures);
    assertExceptionHandling(exceptionKind, captures);
    assertLines(exceptionKind, captures);
  }

  private void assertEntryExit(
      DebuggerTransformerTest.ExceptionKind exceptionKind, Snapshot.Captures captures) {
    CapturedContext expectedEntryContext =
        new CapturedContext(enterArgs.get(exceptionKind), null, null, null, correlationFields);
    if (expectedLineFrom != -1) {
      // no entry/exit for line probe
      return;
    }
    addThis(expectedEntryContext, captures.getEntry());
    assertEquals(expectedEntryContext, captures.getEntry());
    if (exceptionKind != DebuggerTransformerTest.ExceptionKind.UNHANDLED) {
      // no return context is captured for unhandled exceptions
      CapturedContext expectedExitContext =
          new CapturedContext(
              returnArgs.get(exceptionKind),
              returnLocals,
              returnValues.get(exceptionKind),
              null,
              correlationFields);
      addThis(expectedExitContext, captures.getReturn());
      assertEquals(expectedExitContext, captures.getReturn());
    } else {
      assertNotNull(captures.getReturn().getThrowable());
    }
  }

  private void addThis(CapturedContext expected, CapturedContext context) {
    if (context.getArguments().containsKey("this")) {
      expected.getArguments().put("this", context.getArguments().get("this"));
    }
  }

  private void assertExceptionHandling(
      DebuggerTransformerTest.ExceptionKind exceptionKind, Snapshot.Captures captures) {
    if (expectedLineFrom != -1) {
      // no exception for line probe
      return;
    }
    if (exceptionKind == DebuggerTransformerTest.ExceptionKind.UNHANDLED) {
      CapturedContext expectedUnhandled =
          new CapturedContext(
              returnArgs.get(exceptionKind), null, null, UNHANDLED_EXCEPTION, correlationFields);
      assertEquals(
          expectedUnhandled.getThrowable().getMessage(),
          captures.getReturn().getThrowable().getMessage());
      assertEquals(
          expectedUnhandled.getThrowable().getType(),
          captures.getReturn().getThrowable().getType());
    } else {
      assertNull(captures.getReturn().getThrowable());
    }
    if (exceptionKind == DebuggerTransformerTest.ExceptionKind.HANDLED) {
      assertEquals(
          HANDLED_EXCEPTION.getMessage(), captures.getCaughtExceptions().get(0).getMessage());
      assertEquals(HANDLED_EXCEPTION.getType(), captures.getCaughtExceptions().get(0).getType());
    } else {
      assertNull(captures.getCaughtExceptions());
    }
  }

  private void assertLines(
      DebuggerTransformerTest.ExceptionKind exceptionKind, Snapshot.Captures captures) {
    boolean isSingleline = expectedLineFrom == expectedLineTill;
    if (instrumentationKind != DebuggerTransformerTest.InstrumentationKind.ENTRY_EXIT
        && hasLineInfo(debugInfo)) {
      CapturedContext expectedBefore =
          new CapturedContext(
              enterArgs.get(exceptionKind), beforeLocals, null, null, correlationFields);
      assertEquals(expectedBefore, captures.getLines().get(expectedLineFrom));

      if (!isSingleline
          && (exceptionKind == DebuggerTransformerTest.ExceptionKind.NONE
              || instrumentationKind == DebuggerTransformerTest.InstrumentationKind.LINE)) {
        CapturedContext expectedAfter =
            new CapturedContext(
                enterArgs.get(exceptionKind), afterLocals, null, null, correlationFields);
        assertEquals(expectedAfter, captures.getLines().get(expectedLineTill));
      } else {
        // unhandled exception will cause jumping out before hitting the last line in the range
        if (!isSingleline) {
          assertNull(captures.getLines().get(expectedLineTill));
        }
      }
    } else {
      assertNull(captures.getLines());
    }
  }

  private void setupReturnValues() {
    for (DebuggerTransformerTest.ExceptionKind exceptionKind :
        EnumSet.allOf(DebuggerTransformerTest.ExceptionKind.class)) {
      switch (exceptionKind) {
        case HANDLED:
        case NONE:
          {
            returnValues.put(
                exceptionKind, CapturedContext.CapturedValue.of(returnType, returnValue));
            break;
          }
      }
    }
  }

  private void setupLocals(DebuggerTransformerTest.InstrumentationKind instrumentationKind) {
    if (hasVariableInfo(debugInfo)) {
      switch (instrumentationKind) {
        case LINE:
          {
            returnLocals =
                new CapturedContext.CapturedValue[] {
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.VAR_NAME, argumentType, argumentOutputValue)
                };
            beforeLocals =
                new CapturedContext.CapturedValue[] {
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.SCOPED_VAR_NAME,
                      DebuggerTransformerTest.SCOPED_VAR_TYPE,
                      DebuggerTransformerTest.SCOPED_VAR_VALUE),
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.VAR_NAME, argumentType, argumentInputValue)
                };
            afterLocals =
                new CapturedContext.CapturedValue[] {
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.SCOPED_VAR_NAME,
                      DebuggerTransformerTest.SCOPED_VAR_TYPE,
                      DebuggerTransformerTest.SCOPED_VAR_VALUE),
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.VAR_NAME, argumentType, argumentOutputValue)
                };
            break;
          }
        case ENTRY_EXIT:
          {
            returnLocals =
                new CapturedContext.CapturedValue[] {
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.VAR_NAME, argumentType, argumentOutputValue)
                };
            beforeLocals =
                new CapturedContext.CapturedValue[] {
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.VAR_NAME, argumentType, argumentInputValue)
                };
            afterLocals =
                new CapturedContext.CapturedValue[] {
                  CapturedContext.CapturedValue.of(
                      DebuggerTransformerTest.VAR_NAME, argumentType, argumentOutputValue)
                };
            break;
          }
      }
    }
  }

  private void setupArguments() {
    for (DebuggerTransformerTest.ExceptionKind exceptionKind :
        EnumSet.allOf(DebuggerTransformerTest.ExceptionKind.class)) {
      enterArgs.put(
          exceptionKind,
          new CapturedContext.CapturedValue[] {
            CapturedContext.CapturedValue.of(expectedArgNames[0], argumentType, argumentInputValue),
            CapturedContext.CapturedValue.of(expectedArgNames[1], "int", exceptionKind.ordinal())
          });
      returnArgs.put(
          exceptionKind,
          new CapturedContext.CapturedValue[] {
            // the first argument will not get mutated if an exception is thrown before reaching the
            // line of code
            CapturedContext.CapturedValue.of(
                expectedArgNames[0],
                argumentType,
                exceptionKind == DebuggerTransformerTest.ExceptionKind.NONE
                    ? argumentOutputValue
                    : argumentInputValue),
            // exception handler will overwrite the second argument with value of -1
            CapturedContext.CapturedValue.of(
                expectedArgNames[1],
                "int",
                exceptionKind == DebuggerTransformerTest.ExceptionKind.HANDLED
                    ? -1
                    : exceptionKind.ordinal())
          });
    }
  }

  private static boolean hasVariableInfo(SourceCompiler.DebugInfo debugInfo) {
    return debugInfo == SourceCompiler.DebugInfo.ALL
        || debugInfo == SourceCompiler.DebugInfo.VARIABLES;
  }

  private static boolean hasLineInfo(SourceCompiler.DebugInfo debugInfo) {
    return debugInfo == SourceCompiler.DebugInfo.ALL || debugInfo == SourceCompiler.DebugInfo.LINES;
  }
}
