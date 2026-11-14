---
name: release
description: Release EduIDE-Cloud. Use when asked to release, cut a release, make or publish a new version, ship a version, tag a version, or prepare a release candidate of EduIDE-Cloud. Finds the unreleased changes, proposes the version, checks the EduIDE dependencies it pins, writes CHANGELOG.md, opens the release pull request and runs release.yml.
---

# Releasing EduIDE-Cloud

A release is two steps with a human in between:

1. a **release pull request** that bumps the version files and moves the
   unreleased CHANGELOG entries under the new version, and
2. after it is merged, a run of **`release.yml`** (`workflow_dispatch`, input
   `version`), which validates, tags, creates the GitHub Release from
   the CHANGELOG section, then builds, publishes and verifies.

Nothing releases on push to `main`. Tags look like `vX.Y.Z`;
images, charts and vsix files carry `X.Y.Z` without the prefix.

## Reference for this repository

Version files: none. The build stamps the version from the tag: POMs use `${revision}` (`-Drevision` from the image tag) and the monitor vsix is stamped with `npm version`. The `1.2.0-next` in the npm packages stays.

What a release publishes:

| Artifact | Where | Check |
|---|---|---|
| Images `operator`, `service`, `conversion-webhook` | `ghcr.io/eduide/eduide-cloud/<component>:X.Y.Z`, amd64 + arm64 | `docker buildx imagetools inspect ghcr.io/eduide/eduide-cloud/<component>:X.Y.Z` lists both architectures (the `verify` job does this) |
| Monitor extension | release asset `theia-cloud-monitor-X.Y.Z.vsix`, attached by the `attach-monitor` job | `gh release view vX.Y.Z -R EduIDE/EduIDE-Cloud --json assets --jq ".assets[].name"` |
| GitHub Release | `v` tag + notes from CHANGELOG.md | `gh release view vX.Y.Z -R EduIDE/EduIDE-Cloud` |

EduIDE dependencies this repository pins: none.

Who pins this repository (tell the user after the release):

| Repository | Pinned in |
|---|---|
| EduIDE | `images/base-ide/package.json.patch`, `theiaPlugins.theia-cloud-monitor` (release asset URL, version appears twice) |
| EduIDE-Helm | `charts/eduide/values.yaml`, `versions.cloud` (operator and service image tag) |

## 1. Find the last release and what is unreleased

```bash
gh release list -R EduIDE/EduIDE-Cloud --limit 10
LAST=$(gh release list -R EduIDE/EduIDE-Cloud --exclude-pre-releases --limit 1 --json tagName --jq '.[0].tagName')
git fetch origin --tags
git log --first-parent --format='%h %s' "$LAST"..origin/main
```

Read the merged pull requests behind those commits, not only the titles:

```bash
gh pr list -R EduIDE/EduIDE-Cloud --state merged --base main --limit 100 \
  --search "merged:>$(git log -1 --format=%cI "$LAST")" \
  --json number,title,labels,body,url
```

Also read the `## [Unreleased]` section of `CHANGELOG.md`. If there are no
commits since `$LAST`, say so and stop, unless a dependency bump (step 3) is
the reason for the release.

## 2. Propose a version and let the user decide

Derive it from the changes since `$LAST` (conventional commits and the PR
content, not only the prefix):

| Change | Bump |
|---|---|
| `!` after the type, `BREAKING CHANGE:`, a removed or renamed option, a changed default that alters behaviour, a dependency pin that requires a breaking upgrade | major (minor while the version is `0.x`) |
| `feat`, a new option, a dependency pin that brings features | minor |
| `fix`, `perf`, security fixes, dependency pins with fixes only | patch |
| only `chore`, `ci`, `docs`, `test`, `build` | usually nothing to release; ask |

A prerelease is `X.Y.Z-rc.N`, only when the user asks for one or wants to test
before a stable release. `N` starts at 1 and continues after the highest
existing `-rc.N` of that `X.Y.Z`.

**Ask the user** with a question (the AskUserQuestion tool when available):
the proposed version as the first option, with a one-line reason
("minor: 2 features, 3 fixes, no breaking change"), the next lower and higher
bump, and the release candidate variant. Never continue on a guessed version.

## 3. Check every EduIDE dependency

For each row of "EduIDE dependencies":

1. Read the current pin from the file.
2. Find the latest release of that repository:
   `gh release list -R EduIDE/<repo> --exclude-pre-releases --limit 1`
   (for a prerelease of this repository, also look at its prereleases).
3. Check whether it has unreleased changes on main:
   `gh api repos/EduIDE/<repo>/compare/<its last tag>...main --jq '.ahead_by, (.commits[].commit.message | split("\n")[0])'`

Then, per dependency:

- **Pin is older than the latest release:** show the user both versions and
  the dependency's release notes in between, and ask whether to bump the pin
  in this release. A bump goes into the release pull request and into the
  `### Dependencies` notes.
- **Dependency has unreleased changes on main:** list them and ask whether to
  release that repository first. If yes, follow that repository's release
  skill completely before you continue here:
  - local clone (the EduIDE workspace keeps repositories side by side):
    `../<repo>/.claude/skills/release/SKILL.md`
  - otherwise read it with
    `gh api repos/EduIDE/<repo>/contents/.claude/skills/release/SKILL.md --jq .content | base64 -d`
    and work in a fresh clone or worktree of that repository, never in a
    checkout that has someone else's uncommitted work.

  When it is released, come back here and bump the pin to the new version.
- **Pin is current and the dependency has nothing unreleased:** report it as
  up to date, no question needed.

Never pin an unreleased build (`pr-N`, a commit SHA, `latest`) in a stable
release. If the repository pins one today, say so and ask how to resolve it.

## 4. Write the release notes into CHANGELOG.md

Move the content of `## [Unreleased]` under a new heading and leave an empty
`## [Unreleased]` above it. release.yml copies exactly this section into the
GitHub Release, so write it for the people who install or use the release,
not for the people who wrote the code:

```markdown
## [Unreleased]

## [X.Y.Z] - YYYY-MM-DD

### Breaking changes

- What changed, what a user has to do about it ([#123](https://github.com/EduIDE/EduIDE-Cloud/pull/123))

### Features

- What a user can now do, in one sentence ([#124](https://github.com/EduIDE/EduIDE-Cloud/pull/124))

### Fixes

- What was wrong, as the user saw it, and that it no longer happens ([#125](https://github.com/EduIDE/EduIDE-Cloud/pull/125))

### Dependencies

- EduIDE-Cloud 1.3.0 -> 1.4.0: operator metrics ([release](https://github.com/EduIDE/EduIDE-Cloud/releases/tag/v1.4.0))
```

Rules:

- Every entry links its pull request. Find it with `gh pr list --search <sha>`
  when the Unreleased entry has none.
- Leave out empty groups. Merge entries that describe one change. Leave out
  pure CI, test and refactoring changes unless a user can notice them.
- Keep existing per-entry conventions of this file (for example `[eduide]`
  prefixes in EduIDE-Helm, `**Security:**` markers).
- Add `### Upgrade notes` when an operator has to do something beyond
  installing the new version.
- Use hyphens, never em or en dashes.

## 5. Open the release pull request

On a branch `release/X.Y.Z` from the latest `origin/main`:

1. Write the CHANGELOG section from step 4.
2. Run the repository's checks (see AGENTS.md / CI) and anything a version
   bump regenerates.

Show the user the diff and ask before committing and pushing. Then:

```bash
git commit -m "chore(release): X.Y.Z"
git push -u origin release/X.Y.Z
gh pr create -R EduIDE/EduIDE-Cloud --title "chore(release): X.Y.Z" --body-file <notes plus the dependency table>
```

Give the user the PR link, watch CI and fix failures. Do not merge it yourself
unless the user asks.

**Release candidate:** a `-rc.N` needs no version bump, but release.yml still
refuses a version without a `## [X.Y.Z-rc.N]` section in CHANGELOG.md. Put that
section on the branch to test and dispatch from it (step 6 with
`--ref <branch>`). A prerelease never becomes "latest".

## 6. Run release.yml after the merge

```bash
gh workflow run release.yml -R EduIDE/EduIDE-Cloud --ref main -f version=X.Y.Z
sleep 5
RUN=$(gh run list -R EduIDE/EduIDE-Cloud --workflow release.yml --event workflow_dispatch --limit 1 --json databaseId --jq '.[0].databaseId')
gh run watch "$RUN" -R EduIDE/EduIDE-Cloud --exit-status
```

If a job waits for an environment approval (`prod`), tell the user to approve
it in the run page. If `validate` fails, the message says what to fix; fix it
in a new pull request, never by editing tags. If a later job fails, fix the
cause and use "Re-run failed jobs": the tag stays, nothing is published twice.
Never delete a tag or a release once something was published under it; ship
the next patch version instead.

## 7. Verify what was published

Check every row of "What a release publishes", for example:

```bash
gh release view vX.Y.Z -R EduIDE/EduIDE-Cloud --json url,isPrerelease,assets --jq '{url, isPrerelease, assets: [.assets[].name]}'
crane manifest ghcr.io/eduide/<image>:X.Y.Z | jq -r '[.manifests[].platform.architecture] | join(",")'   # amd64 and arm64
helm show chart oci://ghcr.io/eduide/charts/<chart> --version X.Y.Z
curl -fsS https://open-vsx.org/api/<namespace>/<name>/X.Y.Z | jq -r .version
```

Report to the user: the release link, the artifacts with their check result,
and every row of "Who pins this repository" with its current pin, so they can
decide whether to bump it next (that repository's release skill does it).

## Repository notes

- `CHANGELOG.md` `## [Unreleased]` still holds everything since 1.2.0, although 1.2.1 to 1.4.0 shipped. The first release PR splits it into one section per release; backfill the headings from `gh release list -R EduIDE/EduIDE-Cloud`. Entries keep the `- [component] text ([#N](link))` form.
- A `-rc.N` attaches the monitor vsix too.
- The conversion webhook image is built and verified, but EduIDE-Helm's cluster chart does not deploy it yet.
