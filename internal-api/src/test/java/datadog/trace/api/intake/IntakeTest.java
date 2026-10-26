package datadog.trace.api.intake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class IntakeTest extends DDJavaSpecification {

  @Test
  void intakeUrlsAreGeneratedCorrectly() {
    Config config = mock(Config.class);
    when(config.getSite()).thenReturn("datadoghq.com");

    // when
    String apiUrl = Intake.API.getAgentlessUrl(config);
    String llmObsUrl = Intake.LLMOBS_API.getAgentlessUrl(config);
    String logsUrl = Intake.LOGS.getAgentlessUrl(config);

    // then
    assertEquals("https://api.datadoghq.com/api/v2/", apiUrl);
    assertEquals("https://api.datadoghq.com/api/v2/", llmObsUrl);
    assertEquals("https://http-intake.logs.datadoghq.com/api/v2/", logsUrl);

    // when
    when(config.getCiVisibilityAgentlessUrl()).thenReturn("agentless-civis");
    when(config.getLlMObsAgentlessUrl()).thenReturn("agentless-llmobs");
    when(config.getAgentlessLogSubmissionUrl()).thenReturn("agentless-log");
    apiUrl = Intake.API.getAgentlessUrl(config);
    llmObsUrl = Intake.LLMOBS_API.getAgentlessUrl(config);
    logsUrl = Intake.LOGS.getAgentlessUrl(config);

    // then
    assertEquals("agentless-civis/api/v2/", apiUrl);
    assertEquals("agentless-llmobs/api/v2/", llmObsUrl);
    assertEquals("agentless-log/api/v2/", logsUrl);
  }
}
