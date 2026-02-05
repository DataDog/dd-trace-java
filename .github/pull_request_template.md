# What Does This Do

# Motivation

# Additional Notes

# Contributor Checklist

***Note:*** **Once your PR is ready to merge, add it to the merge queue by commenting `/merge`.** `/merge -c` cancels the queue request. For more information, see [this doc](https://datadoghq.atlassian.net/wiki/spaces/DEVX/pages/3121612126/MergeQueue).

- Format the title according to [the contribution guidelines](https://github.com/DataDog/dd-trace-java/blob/master/CONTRIBUTING.md#title-format)
- Assign the `type:` and (`comp:` or `inst:`) labels in addition to [any other useful labels](https://github.com/DataDog/dd-trace-java/blob/master/CONTRIBUTING.md#labels)
- Avoid using `close`, `fix`, or [any linking keywords](https://docs.github.com/en/issues/tracking-your-work-with-issues/linking-a-pull-request-to-an-issue#linking-a-pull-request-to-an-issue-using-a-keyword) when referencing an issue  
  Use `solves` instead, and assign the PR [milestone](https://github.com/DataDog/dd-trace-java/milestones) to the issue
- Update the [CODEOWNERS](https://github.com/DataDog/dd-trace-java/blob/master/.github/CODEOWNERS) file on source file addition, migration, or deletion
- Update [public documentation](https://docs.datadoghq.com/tracing/trace_collection/library_config/java/) with any new configuration flags or behaviors

Jira ticket: [PROJ-IDENT]

<!--
# Opening vs Drafting a PR:
When opening a pull request, please open it as a draft to not auto assign reviewers before you feel the pull request is in a reviewable state.

# Linking a JIRA ticket:
Please link your JIRA ticket by adding its identifier between brackets (ex [PROJ-IDENT]) in the PR description, not the title.
This requirement only applies to Datadog employees.
-->
