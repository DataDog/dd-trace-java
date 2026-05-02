package datadog.trace.common;

import datadog.trace.api.git.GitInfo;
import datadog.trace.api.git.GitInfoProvider;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.Collection;

public class GitMetadataTraceInterceptor extends AbstractTraceInterceptor {

  public static final TraceInterceptor INSTANCE =
      new GitMetadataTraceInterceptor(Priority.GIT_METADATA);

  protected GitMetadataTraceInterceptor(Priority priority) {
    super(priority);
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    if (trace.isEmpty()) {
      return trace;
    }

    final DDSpan firstSpan = (DDSpan) trace.iterator().next();
    String ciWorkspacePath = (String) firstSpan.getTag(Tags.CI_WORKSPACE_PATH);

    GitInfo gitInfo = GitInfoProvider.INSTANCE.getGitInfo(ciWorkspacePath);
    gitInfo.addTags(firstSpan);

    return trace;
  }
}
