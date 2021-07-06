package datadog.trace.core.checkpointermanager;

import datadog.trace.api.SpanCheckpointer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SpanCheckpointerManager implements SpanCheckpointer {
    private static final Logger log = LoggerFactory.getLogger(SpanCheckpointerManager.class);

    private final List<SpanCheckpointer> spanCheckpointers;

    public SpanCheckpointerManager() {
        spanCheckpointers = new CopyOnWriteArrayList<>();
    }

    public void checkpoint(AgentSpan span, int flags) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.checkpoint(span, flags);
        }
    }

    public void onStart(AgentSpan span) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.onStart(span);
        }
    }

    public void onStartWork(AgentSpan span) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.onStartWork(span);
        }
    }

    public void onFinishWork(AgentSpan span) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.onFinishWork(span);
        }
    }

    public void onStartThreadMigration(AgentSpan span) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.onStartThreadMigration(span);
        }
    }

    public void onFinishThreadMigration(AgentSpan span) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.onFinishThreadMigration(span);
        }
    }

    public void onFinish(AgentSpan span) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.onFinish(span);
        }
    }

    public void onRootSpan(AgentSpan root, boolean published) {
        for (final SpanCheckpointer checkpointer : spanCheckpointers) {
            checkpointer.onRootSpan(root, published);
        }
    }

    public void register(final SpanCheckpointer checkpointer) {
        spanCheckpointers.add(checkpointer);
        log.info("Added span checkpointer {}", checkpointer);
    }
}