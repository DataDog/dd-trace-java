#!/bin/bash
tests=( "$@" )
runners=${CI_NODE_TOTAL-1}
runner_id=$((${CI_NODE_INDEX-0} - 1))

if (($runners <= $runner_id)); then
  echo "ERROR: runner id can't be equal or higher than number of runners" >&2
  exit 1
fi
num_tests="${#tests[@]}"

# We need to split number of tests equally among runners
# if the division leaves a reminder, we need to spread the reminder among first $remainder number of nodes

tests_cnt=$(($num_tests / $runners))
remainder=$(($num_tests % $runners))

start_offset=$(( $runner_id > $remainder ? $remainder : $runner_id ))
tests_start=$(($tests_cnt*$runner_id + $start_offset))

if (( $runner_id < $remainder )); then
  tests_cnt=$(($tests_cnt + 1))
fi


if (( tests_cnt == 0 )); then
  echo no tests to run >&2 
else
  echo "${tests[@]:$tests_start:$tests_cnt}"
fi
