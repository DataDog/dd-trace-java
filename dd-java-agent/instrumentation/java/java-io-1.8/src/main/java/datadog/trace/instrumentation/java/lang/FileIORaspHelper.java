package datadog.trace.instrumentation.java.lang;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.File;
import java.net.URI;
import java.nio.file.FileSystems;
import java.util.function.BiFunction;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileIORaspHelper {

  public static FileIORaspHelper INSTANCE = new FileIORaspHelper();

  private static final Logger LOGGER = LoggerFactory.getLogger(FileIORaspHelper.class);

  private FileIORaspHelper() {
    // prevent instantiation
  }

  public void beforeFileLoaded(@Nullable final String parent, @Nonnull final String child) {
    try {
      if (parent == null) {
        beforeFileLoaded(child);
        return;
      }
      String s = parent + FileSystems.getDefault().getSeparator() + child;
      beforeFileLoaded(s);
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during LFI rasp callback", e);
    }
  }

  public void beforeFileLoaded(@Nonnull final String first, @Nonnull final String[] more) {
    try {
      String separator = FileSystems.getDefault().getSeparator();
      String s = first;
      if (more.length > 0) {
        s += separator + String.join(separator, more);
      }
      beforeFileLoaded(s);
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during LFI rasp callback", e);
    }
  }

  public void beforeFileLoaded(@Nonnull final URI uri) {
    try {
      beforeFileLoaded(uri.toString());
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during LFI rasp callback", e);
    }
  }

  public void beforeFileLoaded(@Nullable final File parent, @Nonnull final String child) {
    try {
      if (parent == null) {
        beforeFileLoaded(child);
        return;
      }
      String s = parent + FileSystems.getDefault().getSeparator() + child;
      beforeFileLoaded(s);
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during LFI rasp callback", e);
    }
  }

  public void beforeFileLoaded(@Nonnull final String path) {
    invokeRaspCallback(EVENTS.fileLoaded(), path);
  }

  public void beforeFileWritten(@Nonnull final String path) {
    invokeRaspCallback(EVENTS.fileWritten(), path);
  }

  private void invokeRaspCallback(
      EventType<BiFunction<RequestContext, String, Flow<Void>>> eventType,
      @Nonnull final String path) {
    if (!Config.get().isAppSecRaspEnabled()) {
      return;
    }
    try {
      final BiFunction<RequestContext, String, Flow<Void>> callback =
          AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC).getCallback(eventType);

      if (callback == null) {
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

      Flow<Void> flow = callback.apply(ctx, path);
      Flow.Action action = flow.getAction();
      if (action instanceof Flow.Action.RequestBlockingAction) {
        BlockResponseFunction brf = ctx.getBlockResponseFunction();
        if (brf != null) {
          Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
          brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
        }
        throw new BlockingException("Blocked request (for LFI attempt)");
      }
    } catch (final BlockingException e) {
      // re-throw blocking exceptions
      throw e;
    } catch (final Throwable e) {
      // suppress anything else
      LOGGER.debug("Exception during LFI rasp callback", e);
    }
  }
}
