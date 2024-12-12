package datadog.trace.instrumentation.tibcobw5;

import com.tibco.pe.core.ActivityGroup;
import com.tibco.pe.core.ProcessGroup;
import com.tibco.pe.core.Task;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.regex.Pattern;

public class ActivityHelper {

  private static final Pattern SPLITTER = Pattern.compile("/");

  public static class ActivityInfo {
    public final String id;
    public final String name;
    public final String group;
    public final String parent;
    public final boolean trace;

    public ActivityInfo(String id, String name, String group, String parent, boolean trace) {
      this.id = id;
      this.name = name;
      this.group = group;
      this.parent = parent;
      this.trace = trace;
    }
  }

  private static final DDCache<Task, ActivityInfo> ACTIVITY_NAME_GROUP_CACHE =
      DDCaches.newFixedSizeWeakKeyCache(1024);

  public static ActivityInfo activityInfo(final Task task) {
    return ACTIVITY_NAME_GROUP_CACHE.computeIfAbsent(task, ActivityHelper::calculateActivityInfo);
  }

  private static ActivityInfo calculateActivityInfo(final Task task) {
    String[] parts = SPLITTER.split(task.getName());
    String workflow = task.getWorkflow().getName();
    String name = parts[parts.length - 1];
    String group = parts.length > 1 ? parts[parts.length - 2] : workflow;
    String id = group + "/" + name;
    String parentName = group;
    boolean trace = true;

    if (task.getActivity() instanceof ActivityGroup) {
      final boolean groupEnd = task.getWorkflow().getGroupEnd(group) == task;
      if (groupEnd || !(task.getActivity() instanceof ProcessGroup)) {
        id = group;
        name = group;
        parentName = parts.length > 2 ? parts[parts.length - 3] : workflow;
        trace = groupEnd;
      }
    }
    return new ActivityInfo(id, name, group, parentName, trace);
  }
}
