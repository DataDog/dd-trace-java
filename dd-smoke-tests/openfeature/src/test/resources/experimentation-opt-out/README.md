# Experimentation opt-out test cases

`cases.json` covers 90 combinations of opt-out mode, subject timestamp, allocation activation,
and exposure logging. `ExperimentationOptOutProviderTest` runs each case through the production
parser and evaluator and checks the selected allocation or evaluation error.
