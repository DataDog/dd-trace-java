# GitHub Actions Documentation

This lists and describes the repository GitHub actions, how to maintain and test them.

## Release Management

### add-milestone-to-pull-requests [🔗](add-milestone-to-pull-requests.yaml)

_Trigger:_ When a PR targeting `master` or a patch release (`release/vM.N.x`) branch is closed.

_Action:_ Attach the corresponding milestone to the closed pull request (if not set).

_Recovery:_ Attach the milestone by hand to the PR.

### add-release-to-cloudfoundry [🔗](add-release-to-cloudfoundry.yaml)

_Trigger:_ When a release is published.

_Action:_ Append the new release to the Cloud Foundry repository.

_Recovery:_ Manually edit and push the `index.yml` file from [the cloudfoundry branch](https://github.com/DataDog/dd-trace-java/tree/cloudfoundry).

### check-pull-requests [🔗](check-pull-requests.yaml)

_Trigger:_ When creating or updating a pull request.

_Action:_ Check the pull request complies with [the contribution guidelines](https://github.com/DataDog/dd-trace-java/blob/master/CONTRIBUTING.md).

_Recovery:_ Manually verify the guideline compliance.

### check-pull-request-labels [🔗](check-pull-request-labels.yaml)

_Trigger:_ When creating or updating a pull request.

_Action:_ Check the pull request did not introduce unexpected label.

_Recovery:_ Update the pull request or add a comment to trigger the action again.

### enforce-datadog-merge-queue [🔗](enforce-datadog-merge-queue.yaml)

_Trigger:_ When creating or updating a pull request, or when a pull request is added to GitHub merge queue.

_Actions:_

* Pass the `Merge queue check` status check on pull requests so they remain in a mergeable state,
* When a pull request is enqueued in GitHub merge queue, post a `/merge` comment to trigger the Datadog merge queue,
* Fail the `Merge queue check` status check on merge groups to prevent GitHub from merging directly.

_Recovery:_ The workflow is expected to fail to block GitHub merge queue.
This redirects GitHub's "Merge when ready" button to the Datadog merge queue system.

### create-release-branch [🔗](create-release-branch.yaml)

_Trigger:_ When a git tag matching the pattern "vM.N.0" is pushed (e.g. for a minor release).

_Action:_ Create a release branch that corresponds to the pushed tag (e.g. "release/vM.N.x").

_Recovery:_ Manually create the branch from the "vM.N.0" git tag.

### draft-release-notes-on-tag [🔗](draft-release-notes-on-tag.yaml)

_Trigger:_ When creating a tag, or manually (providing a tag)

_Actions:_

* Fetch merged pull requests from the related tag milestone,
* Generate changelog draft,
* Create a new draft release for given tag with the generated changelog.

_Recovery:_ Manually trigger the action again on the relevant tag.

### increment-milestone-on-tag [🔗](increment-milestone-on-tag.yaml)

_Trigger:_ When creating a minor or major version tag.

_Actions:_

* Close the milestone related to the tag,
* Create a new milestone by incrementing minor version.

_Recovery:_ Manually [close the related milestone and create a new one](https://github.com/DataDog/dd-trace-java/milestones).

_Notes:_ This action will not apply to release candidate versions using `-RC` tags.

### prune-old-pull-requests [🔗](prune-old-pull-requests.yaml)

_Trigger:_ Every month or manually.

_Action:_ Mark as stale and comment on pull requests with no update during the last quarter.
Close them if no following update within a week.

_Recovery:_ Manually trigger the action again.

### update-docker-build-image [🔗](update-docker-build-image.yaml)

_Trigger:_ Quarterly released, loosely [a day after the new image tag is created](https://github.com/DataDog/dd-trace-java-docker-build/blob/master/.github/workflows/docker-tag.yml).

_Action:_ Update the Docker build image used in GitLab CI with the latest tag.

_Recovery:_ Download artifacts and upload them manually to the related _download release_.

_Notes:_  Manually trigger the action again given the desired image tag as input.

### update-download-releases [🔗](update-download-releases.yaml)

_Trigger:_ When a release is published.

_Action:_ Update the _download releases_ with the latest release artifact.

_Recovery:_ Download artifacts and upload them manually to the related _download release_.

_Notes:_ _Download releases_ are special GitHub releases with fixed URL and tags, but rolling artifacts to provided stable download links (ex [latest](https://github.com/DataDog/dd-trace-java/releases/tag/download-latest) and [latest-v1](https://github.com/DataDog/dd-trace-java/releases/tag/download-latest-v1)).

### update-issues-on-release [🔗](update-issues-on-release.yaml)

_Trigger:_ When a release is published. Releases of type `prereleased` should skip this.

_Action:_

* Find all issues related to the release by checking the related milestone,
* Add a comment to let know the issue was addressed by the newly published release,
* Close all those issues.

_Recovery:_ Check at the milestone for the related issues and update them manually.

## Code Quality and Security

### analyze-changes [🔗](analyze-changes.yaml)

_Trigger:_ Every day or manually.

_Action:_

* Run [GitHub CodeQL](https://codeql.github.com/) action, upload result to GitHub security tab -- do not apply to pull request, only to `master`,
* Run [Trivy security scanner](https://github.com/aquasecurity/trivy) on built artifacts and upload result to GitHub security tab and Datadog Code Analysis.

_Notes:_ Results are sent on both production and staging environments.

### comment-on-submodule-update [🔗](comment-on-submodule-update.yaml)

_Trigger:_ When creating a PR commits to `master` or a `release/*` branch with a Git Submodule update.

_Action:_ Notify the PR author through comments that about the Git Submodule update.

### run-system-tests [🔗](run-system-tests.yaml)

_Trigger:_ When pushing commits to `master` or manually.

_Action:_ Build the Java Client Library and runs [the system tests](https://github.com/DataDog/system-tests) against.

_Recovery:_ Manually trigger the action on the desired branch.

### update-gradle-dependencies [🔗](update-gradle-dependencies.yaml)

_Trigger:_ Every week or manually.

_Action:_ Create a PR updating the Grade dependencies and their locking files.

_Recovery:_ Manually trigger the action again.

### update-jmxfetch-submodule [🔗](update-jmxfetch-submodule.yaml)

_Trigger:_ Monthly or manually

_Action:_ Creates a PR updating the git submodule at dd-java-agent/agent-jmxfetch/integrations-core

_Recovery:_ Manually trigger the action again.

## Maintenance

GitHub actions should be part of the [repository allowed actions to run](https://github.com/DataDog/dd-trace-java/settings/actions).
While GitHub owned actions are allowed by default, the other ones must be declared.

Run the following script to get the list of actions to declare according the state of your working copy:
```bash
find .github/workflows -name "*.yaml" -exec  awk '/uses:/{print $2 ","}' {} \; | grep -vE '^(actions|github)/' | sed 's/@.*/@*/' | sort | uniq
```

## Testing

Workflows can be locally tested using the [`act` CLI](https://github.com/nektos/act/).
Docker and [GiHub CLI](https://cli.github.com/) need also to be installed.
The [.github/workflows/tests/](./tests) folder contains test scripts and event payloads to locally trigger workflows.

> [!WARNING]
> Local workflow tests run against the repository and will potentially alter existing issues, milestones and releases.  
> Pay extra attention to the workflow jobs you trigger to not create development disruption.
