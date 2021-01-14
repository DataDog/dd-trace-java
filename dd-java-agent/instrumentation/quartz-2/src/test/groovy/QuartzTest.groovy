import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.bootstrap.instrumentation.api.Tags
import org.quartz.CronScheduleBuilder
import org.quartz.JobBuilder
import org.quartz.JobDetail
import org.quartz.Scheduler
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.impl.StdSchedulerFactory
import spock.lang.Shared
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_GROUP
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_NAME
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_REFIRE_COUNT
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_SCHEDULER
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_SCHEDULER_ACTUAL_TIME
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_SCHEDULER_FIRED_TIME
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_GROUP
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_NAME



class QuartzTest extends AgentTestRunner {
  public static final String JOB_NAME = "job"
  public static final String GROUP_NAME = "group"
  public static final String TRIGGER_NAME = "trigger"

  @Shared
  Scheduler scheduler

  def "Test simple trigger scheduling" () {
    setup:
    scheduler = new StdSchedulerFactory().getScheduler()
    def latch = new CountDownLatch(1)

    JobDetail jobDetail = JobBuilder.newJob(QuartzTestJob).withIdentity(JOB_NAME, GROUP_NAME).build()
    jobDetail.getJobDataMap().put("latch", latch)

    Trigger trigger = TriggerBuilder.newTrigger().withIdentity(TRIGGER_NAME, GROUP_NAME).startNow().build()
    scheduler.scheduleJob(jobDetail, trigger)

    when:
    scheduler.start()

    then:
    latch.await(10L, TimeUnit.SECONDS)
    assertTraces(1) {
      trace(1) {
          jobSpan(it, scheduler.getSchedulerName())
      }
    }

    cleanup:
    scheduler.shutdown()
  }

  def "Test cron trigger scheduling"() {
    setup:
    scheduler = new StdSchedulerFactory().getScheduler()
    def latch = new CountDownLatch(1)

    JobDetail jobDetail = JobBuilder.newJob(QuartzTestJob).withIdentity(JOB_NAME, GROUP_NAME).build()
    jobDetail.getJobDataMap().put("latch", latch)

    Trigger cronTrigger = TriggerBuilder.newTrigger()
      .withIdentity(TRIGGER_NAME, GROUP_NAME)
      .forJob(jobDetail)
      .withSchedule(CronScheduleBuilder.cronSchedule("* * * ? * *")).build() // run every second
    scheduler.scheduleJob(jobDetail, cronTrigger)

    when:
    scheduler.start()

    then:
    latch.await(10L, TimeUnit.SECONDS)

    assertTraces(1) {
      trace(1) {
        jobSpan(it, scheduler.getSchedulerName())
      }
    }

    cleanup:
    scheduler.shutdown()
  }

  void jobSpan(TraceAssert trace, String schedulerName) {
    trace.span {
      serviceName "worker.org.gradle.process.internal.worker.GradleWorkerMain"
      operationName "job.instance"
      resourceName QuartzTestJob.toString()
      errored false

      tags {
        "$Tags.COMPONENT" "quartz"
        "$QUARTZ_SCHEDULER" schedulerName
        "$QUARTZ_TRIGGER_NAME" TRIGGER_NAME
        "$QUARTZ_TRIGGER_GROUP" GROUP_NAME
        "$QUARTZ_JOB_NAME" JOB_NAME
        "$QUARTZ_JOB_GROUP" GROUP_NAME
        "$QUARTZ_REFIRE_COUNT" 0
        "$QUARTZ_SCHEDULER_FIRED_TIME" String
        "$QUARTZ_SCHEDULER_ACTUAL_TIME" String
        defaultTags()
      }
    }
  }
}
