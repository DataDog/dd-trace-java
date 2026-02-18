package datadog.trace.bootstrap.instrumentation.api.java.lang;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureActiveSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopContinuation;
import static java.lang.invoke.MethodType.methodType;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This class is included here because it needs to injected into the bootstrap clasloader. */
public class ProcessImplInstrumentationHelpers {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(ProcessImplInstrumentationHelpers.class);

  private static final int LIMIT = 4096;
  public static final boolean ONLINE;
  private static final MethodHandle PROCESS_ON_EXIT;
  private static final Executor EXECUTOR;

  private static final Pattern REDACTED_PARAM_PAT =
      Pattern.compile(
          "^(?i)-{0,2}(?:p(?:ass(?:w(?:or)?d)?)?|api_?key|secret|"
              + "a(?:ccess|uth)_token|mysql_pwd|credentials|(?:stripe)?token)$");
  private static final Set<String> REDACTED_BINARIES = Collections.singleton("md5");

  // This check is used to avoid command injection exploit prevention if shell injection exploit
  // prevention checked
  private static final ThreadLocal<Boolean> checkShi = ThreadLocal.withInitial(() -> false);

  static {
    MethodHandle processOnExit = null;
    Executor executor = null;
    try {
      // java 9
      processOnExit =
          MethodHandles.publicLookup()
              .findVirtual(Process.class, "onExit", methodType(CompletableFuture.class));
    } catch (Throwable e) {
      try {
        // java 8
        Class<?> unixProcessCls =
            ClassLoader.getSystemClassLoader().loadClass("java.lang.UNIXProcess");
        Field f = unixProcessCls.getDeclaredField("processReaperExecutor");
        f.setAccessible(true);
        executor = (Executor) f.get(null);
      } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException ex) {
      }
    }
    PROCESS_ON_EXIT = processOnExit;
    EXECUTOR = executor;
    ONLINE = PROCESS_ON_EXIT != null || EXECUTOR != null;
  }

  private ProcessImplInstrumentationHelpers() {}

  public static void setTags(final AgentSpan span, final String[] origCommand) {
    String[] command;
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      command = new String[] {origCommand[0]};
    } else {
      command = origCommand;
    }
    command = redact(command);
    StringBuilder sb = new StringBuilder("[");
    long remaining = LIMIT;
    for (int i = 0; i < command.length; i++) {
      String cur = command[i];
      remaining -= cur.length();
      if (remaining < 0) {
        span.setTag("cmd.truncated", "true");
        break;
      }
      if (i != 0) {
        sb.append(',');
      }
      sb.append('"');
      sb.append(cur.replace("\\", "\\\\").replace("\"", "\\\""));
      sb.append('"');
    }
    sb.append(']');
    span.setTag("cmd.exec", sb.toString());
  }

  private static String[] redact(String[] command) {
    if (command.length == 0) {
      return command;
    }

    String[] newCommand = null;
    if (REDACTED_BINARIES.contains(determineResource(command))) {
      newCommand = new String[command.length];
      newCommand[0] = command[0];
      for (int i = 1; i < command.length; i++) {
        newCommand[i] = "?";
      }
      return newCommand;
    }

    boolean redactNext = false;
    for (int i = 1; i < command.length; i++) {
      if (redactNext) {
        if (newCommand == null) {
          newCommand = new String[command.length];
          System.arraycopy(command, 0, newCommand, 0, command.length);
        }
        newCommand[i] = "?";
        redactNext = false;
        continue;
      }

      String s = command[i];
      if (s == null) {
        continue;
      }
      int posEqual = s.indexOf('=');
      if (posEqual == -1) {
        if (REDACTED_PARAM_PAT.matcher(s).matches()) {
          redactNext = true;
        }
      } else {
        String param = s.substring(0, posEqual);
        if (REDACTED_PARAM_PAT.matcher(param).matches()) {
          if (newCommand == null) {
            newCommand = new String[command.length];
            System.arraycopy(command, 0, newCommand, 0, command.length);
          }
          newCommand[i] = param + "=?";
        }
      }
    }

    return newCommand != null ? newCommand : command;
  }

  public static void addProcessCompletionHook(Process p, AgentSpan span) {
    if (PROCESS_ON_EXIT != null) {
      CompletableFuture<Process> future;
      try {
        future = (CompletableFuture<Process>) PROCESS_ON_EXIT.invokeExact(p);
      } catch (Throwable e) {
        span.finish();
        if (e instanceof Error) {
          throw (Error) e;
        } else if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        } else {
          throw new UndeclaredThrowableException(e);
        }
      }

      final AgentScope.Continuation continuation = captureActiveSpan();
      future.whenComplete(
          (process, thr) -> {
            if (thr != null) {
              span.addThrowable(thr);
            } else {
              span.setTag("cmd.exit_code", Integer.toString(process.exitValue()));
            }
            finishSpan(continuation, span);
          });
    } else if (EXECUTOR != null) {
      final AgentScope.Continuation continuation = captureActiveSpan();
      EXECUTOR.execute(
          () -> {
            try {
              int exitCode = p.waitFor();
              span.setTag("cmd.exit_code", Integer.toString(exitCode));
            } catch (InterruptedException e) {
              span.addThrowable(e);
            } finally {
              finishSpan(continuation, span);
            }
          });
    }
  }

  public static CharSequence determineResource(String[] command) {
    String first = command[0];
    int pos = first.lastIndexOf('/');
    if (pos == -1 || pos == first.length() - 1) {
      return first;
    }
    return first.substring(pos + 1);
  }

  /*
   Check if there is a cmd injection attempt to block it
  */
  public static void cmdiRaspCheck(@Nonnull final String[] cmdArray) {
    if (!Config.get().isAppSecRaspEnabled()) {
      return;
    }
    // if shell injection was checked, skip cmd injection check
    if (checkShi.get()) {
      return;
    }
    try {
      final BiFunction<RequestContext, String[], Flow<Void>> execCmdCallback =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.execCmd());

      if (execCmdCallback == null) {
        return;
      }

      final AgentSpan span = AgentTracer.get().activeSpan();
      if (span == null) {
        return;
      }

      final RequestContext ctx = span.getRequestContext();
      if (ctx == null) {
        return;
      }

      Flow<Void> flow = execCmdCallback.apply(ctx, cmdArray);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        BlockResponseFunction brf = ctx.getBlockResponseFunction();
        if (brf != null) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
          brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
        }
        throw new BlockingException("Blocked request (for CMDI attempt)");
      }
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during CMDI rasp callback", e);
    }
  }

  public static void resetCheckShi() {
    checkShi.set(false);
  }

  /*
   Check if there is a chell injection attempt to block it
  */
  public static void shiRaspCheck(@Nonnull final String cmd) {
    if (!Config.get().isAppSecRaspEnabled()) {
      return;
    }
    checkShi.set(true);
    try {
      final BiFunction<RequestContext, String, Flow<Void>> shellCmdCallback =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.shellCmd());

      if (shellCmdCallback == null) {
        return;
      }

      final AgentSpan span = AgentTracer.get().activeSpan();
      if (span == null) {
        return;
      }

      final RequestContext ctx = span.getRequestContext();
      if (ctx == null) {
        return;
      }

      Flow<Void> flow = shellCmdCallback.apply(ctx, cmd);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        BlockResponseFunction brf = ctx.getBlockResponseFunction();
        if (brf != null) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
          brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
        }
        throw new BlockingException("Blocked request (for SHI attempt)");
      }
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during SHI rasp callback", e);
    }
  }

  private static void finishSpan(
      final AgentScope.Continuation parentContinuation, final AgentSpan span) {
    if (parentContinuation == noopContinuation()) {
      span.finish();
      return;
    }
    try (final AgentScope scope = parentContinuation.activate()) {
      span.finish();
    }
  }
}
