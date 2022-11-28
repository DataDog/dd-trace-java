package datadog.trace.bootstrap.instrumentation.api.java.lang;

import static java.lang.invoke.MethodType.methodType;

import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/** This class is included here because it needs to injected into the bootstrap clasloader. */
public class ProcessImplInstrumentationHelpers {
  private static final int LIMIT = 4096;
  public static final boolean ONLINE;
  private static final MethodHandle PROCESS_ON_EXIT;
  private static final Executor EXECUTOR;

  private static final Pattern REDACTED_PARAM_PAT =
      Pattern.compile(
          "^(?i)-{0,2}(?:p(?:ass(?:w(?:or)?d)?)?|api_?key|secret|"
              + "a(?:ccess|uth)_token|mysql_pwd|credentials|(?:stripe)?token)$");
  private static final Set<String> REDACTED_BINARIES = Collections.singleton("md5");

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

  public static Map<String, String> createTags(String[] origCommand) {
    String[] command;
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      command = new String[] {origCommand[0]};
    } else {
      command = origCommand;
    }
    command = redact(command);
    Map<String, String> ret = new HashMap<>(4);
    StringBuilder sb = new StringBuilder("[");
    long remaining = LIMIT;
    for (int i = 0; i < command.length; i++) {
      String cur = command[i];
      remaining -= cur.length();
      if (remaining < 0) {
        ret.put("cmd.truncated", "true");
        break;
      }
      if (i != 0) {
        sb.append(',');
      }
      sb.append('"');
      sb.append(cur.replace("\\", "\\\\").replace("\"", "\\\""));
      sb.append('"');
    }
    sb.append("]");
    ret.put("cmd.exec", sb.toString());
    return ret;
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
        if (e instanceof Error) {
          throw (Error) e;
        } else if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        } else {
          throw new UndeclaredThrowableException(e);
        }
      }

      future.whenComplete(
          (process, thr) -> {
            if (thr != null) {
              span.setError(true);
              span.setErrorMessage(thr.getMessage());
            } else {
              span.setTag("cmd.exit_code", process.exitValue());
            }
            span.finish();
          });
    } else if (EXECUTOR != null) {
      EXECUTOR.execute(
          () -> {
            try {
              int exitCode = p.waitFor();
              span.setTag("cmd.exit_code", exitCode);
            } catch (InterruptedException e) {
              span.setError(true);
              span.setErrorMessage(e.getMessage());
            }
            span.finish();
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
}
