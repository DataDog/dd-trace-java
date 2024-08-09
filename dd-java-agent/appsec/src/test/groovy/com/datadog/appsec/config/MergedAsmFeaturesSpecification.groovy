package com.datadog.appsec.config

import datadog.trace.api.UserIdCollectionMode
import spock.lang.Specification

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION
import static datadog.trace.api.UserIdCollectionMode.DISABLED

class MergedAsmFeaturesSpecification extends Specification {

  void 'test merging and removing asm activation'() {
    setup:
    final features = new MergedAsmFeatures()

    when:
    features.addConfig('asm_activation_1', asm(false))

    then:
    !features.mergedData.asm.enabled

    when:
    features.addConfig('asm_activation_1', asm(true))

    then:
    features.mergedData.asm.enabled
  }

  void 'test merging and removing api security sampling'() {
    setup:
    final features = new MergedAsmFeatures()

    when:
    features.addConfig('api_security_1', apiSecurity(2.0))

    then:
    features.mergedData.apiSecurity.requestSampleRate == 2.0

    when:
    features.addConfig('api_security_1', apiSecurity(3.0))

    then:
    features.mergedData.apiSecurity.requestSampleRate == 3.0
  }

  void 'test merging and removing auto user instrum'() {
    setup:
    final features = new MergedAsmFeatures()

    when:
    features.addConfig('auto_user_instrum_1', autoUserInstrum(ANONYMIZATION))

    then:
    features.mergedData.autoUserInstrum.mode == ANONYMIZATION.toString()

    when:
    features.addConfig('auto_user_instrum_1', autoUserInstrum(DISABLED))

    then:
    features.mergedData.autoUserInstrum.mode == DISABLED.toString()
  }

  private static AppSecFeatures asm(boolean enabled) {
    return new AppSecFeatures(asm: new AppSecFeatures.Asm(enabled: enabled))
  }

  private static AppSecFeatures apiSecurity(float sampling) {
    new AppSecFeatures(apiSecurity: new AppSecFeatures.ApiSecurity(requestSampleRate: sampling))
  }

  private static AppSecFeatures autoUserInstrum(UserIdCollectionMode mode) {
    new AppSecFeatures(autoUserInstrum: new AppSecFeatures.AutoUserInstrum(mode: mode.toString()))
  }
}
