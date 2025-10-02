import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.scheduling.ScheduledTasksEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@Configuration
@EnableScheduling
public class TriggerTaskConfig {
  @Bean
  public TriggerTask triggerTasks() {
    return new TriggerTask();
  }

  @Bean
  public ScheduledTasksEndpoint scheduledTasksEndpoint(
      ObjectProvider<ScheduledTaskHolder> holders) {
    return new ScheduledTasksEndpoint(holders.orderedStream().collect(Collectors.toSet()));
  }
}
