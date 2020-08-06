#!/bin/bash -xe    

TARGET=$1
cd $TARGET
./gradlew dependencies
./gradlew assemble
