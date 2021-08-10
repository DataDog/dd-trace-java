package datadog.trace.agent.test.checkpoints

class CompositeValidator {
  private validators = []

  CompositeValidator(def ... validators) {
    this.validators = validators
  }

  def onEvent(Event event) {
    validators.each {it.onEvent(event)}
  }

  def onEnd() {
    validators.each {it.endSequence()}
  }

  def invalidEvents() {
    def rslt = new HashSet()
    validators.each {rslt.addAll(it.invalidEvents)}
    return rslt
  }
}
