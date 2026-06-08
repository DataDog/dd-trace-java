import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncErrorTaskConfig {

  @Bean
  AsyncErrorTask asyncErrorTask() {
    return new AsyncErrorTask();
  }
}
