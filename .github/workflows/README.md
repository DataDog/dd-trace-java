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

### create-next-milestone [🔗](create-next-milestone.yaml)

_Trigger:_ When closing a milestone.

_Action:_ Create a new milestone by incrementing minor version.

_Comment:_ Disabled as also covered by increment-milestone-on-tag.
This will be removed after some testing.  

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

### prune-github-container-registry [🔗](prune-github-container-registry.yaml)

_Trigger:_ Every week or manually.

_Action:_ Clean up old lib-injection OCI images from GitHub Container Registry.

_Recovery:_ Manually trigger the action again.

## Code Quality and Security

### analyze-changes [🔗](analyze-changes-with-github-codeql.yaml)

_Trigger:_ When pushing commits to `master` or any pull request targeting `master`.

_Action:_ 
* Run [DataDog Static Analysis](https://docs.datadoghq.com/static_analysis/) and upload result to DataDog Code Analysis,
* Run [GitHub CodeQL](https://codeql.github.com/) action, upload result to GitHub security tab and DataDog Code Analysis -- do not apply to pull request, only when pushing to `master`,
* Run [Trivy security scanner](https://github.com/aquasecurity/trivy) on built artifacts and upload result to GitHub security tab.

### comment-on-submodule-update [🔗](comment-on-submodule-update.yaml)

_Trigger:_ When creating a PR commits to `master` or a `release/*` branch with a Git Submodule update.

_Action:_ Notify the PR author through comments that about the Git Submodule update.

### update-gradle-dependencies [🔗](update-gradle-dependencies.yml)

_Trigger:_ Every week or manually.

_Action:_ Create a PR updating the Grade dependencies and their locking files.

_Recovery:_ Manually trigger the action again.


## Maintenance

GitHub actions should be part of the [repository allowed actions to run](https://github.com/DataDog/dd-trace-java/settings/actions).
While GitHub owned actions are allowed by default, the other ones must be declared.

Run the following script to get the list of actions to declare according the state of your working copy:
```bash
find .github/workflows -name "*.yaml" -exec  awk '/uses:/{print $2 ","}' {} \; | grep -vE '^(actions|github)/' | sort | uniq
```

## Testing

Workflows can be locally tested using the [`act` CLI](https://github.com/nektos/act/).
The [.github/workflows/tests/](./tests) folder contains test scripts and event payloads to locally trigger workflows.

> [!WARNING]
> Locally running workflows will still query GitHub backend and will update the GitHub project accordingly.
> Pay extra attention to the workflow jobs you trigger to not create development disruption.
