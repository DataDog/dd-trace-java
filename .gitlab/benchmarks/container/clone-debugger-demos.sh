#!/usr/bin/env bash

set -e

if [ -n "$CI_JOB_TOKEN" ]; then
  git clone https://gitlab-ci-token:${CI_JOB_TOKEN}@gitlab.ddbuild.io/DataDog/debugger-demos.git
else
  mkdir -p ~/.ssh && ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts
  git clone git@github.com:DataDog/debugger-demos.git
fi


cd debugger-demos/java/petclinic
./gradlew bootJar
cp build/libs/spring-petclinic-2.7.3.jar /spring-petclinic.jar
