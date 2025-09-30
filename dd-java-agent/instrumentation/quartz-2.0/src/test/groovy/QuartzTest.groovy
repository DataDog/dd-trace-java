import datadog.trace.agent.test.InstrumentationSpecification
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

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_GROUP
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_JOB_NAME
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_GROUP
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.QUARTZ_TRIGGER_NAME
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class QuartzTest extends InstrumentationSpecification {
  public static final String JOB_NAME = "job"
  public static final String GROUP_NAME = "group"
  public static final String TRIGGER_NAME = "trigger"

  @Shared
  Scheduler scheduler

  def "Test simple trigger scheduling" () {
    setup:
    scheduler = new StdSchedulerFactory().getScheduler()
    def latch = new CountDownLatch(1)
    scheduler.getContext().put("latch", latch)

    JobDetail jobDetail = JobBuilder.newJob(QuartzTestJob).withIdentity(JOB_NAME, GROUP_NAME).build()
    Trigger trigger = TriggerBuilder.newTrigger().withIdentity(TRIGGER_NAME, GROUP_NAME).startNow().build()
    scheduler.scheduleJob(jobDetail, trigger)

    when:
    scheduler.start()

    then:
    latch.await(10L, TimeUnit.SECONDS)
    assertTraces(1) {
      trace(1) {
        jobSpan(it)
      }
    }

    cleanup:
    scheduler.shutdown()
  }

  def "Test cron trigger scheduling"() {
    setup:
    scheduler = new StdSchedulerFactory().getScheduler()
    def latch = new CountDownLatch(1)
    scheduler.getContext().put("latch", latch)

    JobDetail jobDetail = JobBuilder.newJob(QuartzTestJob).withIdentity(JOB_NAME, GROUP_NAME).build()

    Trigger cronTrigger = TriggerBuilder.newTrigger()
      .withIdentity(TRIGGER_NAME, GROUP_NAME)
      .forJob(jobDetail)
      .withSchedule(CronScheduleBuilder.cronSchedule("0/5 * * ? * *")).build() // run every second
    scheduler.scheduleJob(jobDetail, cronTrigger)

    when:
    scheduler.start()

    then:
    latch.await(10L, TimeUnit.SECONDS)
    assertTraces(1) {
      trace(1) {
        jobSpan(it)
      }
    }

    cleanup:
    scheduler.shutdown()
  }

  def "Test XML job and trigger configuration"() {
    setup:
    scheduler = new StdSchedulerFactory("testConfig.properties").getScheduler()
    def latch = new CountDownLatch(1)
    scheduler.getContext().put("latch", latch)

    when:
    scheduler.start()

    then:
    latch.await(10L, TimeUnit.SECONDS)

    assertTraces(1) {
      trace(1) {
        jobSpan(it)
      }
    }

    cleanup:
    scheduler.shutdown()
  }

  def "Test creating a new trace when starting a job"() {
    setup:
    scheduler = new StdSchedulerFactory().getScheduler()
    def latch = new CountDownLatch(1)
    scheduler.getContext().put("latch", latch)

    when:
    runUnderTrace("root") {
      JobDetail jobDetail = JobBuilder.newJob(QuartzTestJob).withIdentity(JOB_NAME, GROUP_NAME).build()
      Trigger trigger = TriggerBuilder.newTrigger().withIdentity(TRIGGER_NAME, GROUP_NAME).startNow().build()
      scheduler.scheduleJob(jobDetail, trigger)
      scheduler.start()
    }

    then:
    latch.await(10L, TimeUnit.SECONDS)
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "root")
      }
      trace(1) {
        jobSpan(it)
      }
    }

    cleanup:
    scheduler.shutdown()
  }

  void jobSpan(TraceAssert trace) {
    trace.span {
      operationName "scheduled.call"
      resourceName QuartzTestJob.toString()
      errored false

      tags {
        "$Tags.COMPONENT" "quartz"
        "$QUARTZ_TRIGGER_NAME" TRIGGER_NAME
        "$QUARTZ_TRIGGER_GROUP" GROUP_NAME
        "$QUARTZ_JOB_NAME" JOB_NAME
        "$QUARTZ_JOB_GROUP" GROUP_NAME
        defaultTags()
      }
    }
  }
}
