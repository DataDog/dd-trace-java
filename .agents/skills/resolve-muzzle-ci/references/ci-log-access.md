# Read GitLab CI job logs

Follow the failed check's job URL. For a URL shaped like
`https://gitlab.example.com/group/project/-/jobs/12345`, use that host, project path, and job ID;
do not infer them from the GitHub remote.

If `glab` is installed, check authentication for that host and fetch the failed job's log:

```bash
glab auth status --hostname gitlab.example.com
glab ci trace 12345 --repo https://gitlab.example.com/group/project
```

If authentication is missing, have the user authenticate locally with
`glab auth login --hostname gitlab.example.com`, then repeat the status check. Do not request tokens
in chat or use `--show-token`. Authentication to GitHub does not establish access to GitLab.

If `glab` or access is unavailable, use an available authenticated GitLab connector or ask for the
downloaded job log. Continue analysis from supplied logs, noting any missing evidence. Reading a log
does not authorize retrying the job; follow the skill's bounded-retry rule when a retry is authorized.

Command references: [authentication status](https://docs.gitlab.com/cli/auth/status/),
[authentication login](https://docs.gitlab.com/cli/auth/login/), and
[job trace](https://docs.gitlab.com/cli/ci/trace/).
