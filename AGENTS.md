# art_standalone (nexumia-de fork)

Fork of [art_standalone](https://gitlab.com/android_translation_layer/art_standalone)
(Apache-2.0), a dependency of Android Translation Layer (ATL). It
carries fixes the owner's phone needs before upstream has them; the
package `art_standalone@nexumia` at `https://apk.nexumia.de/edge` is built
from `phosh` by [nexumia-de/aports](https://github.com/nexumia-de/aports).

## Branches

- `master` is identical to upstream `master`. Never commit to it;
  `.github/workflows/sync-upstream.yml` mirrors it from GitLab daily.
- `phosh` is the default branch: upstream plus `AGENTS.md`,
  `CLAUDE.md`, `.github/` and fixes not yet merged upstream. Work
  happens on branches from `phosh` and comes in by pull request.
- Upstream changes come in through the pull request master -> phosh that
  the workflow opens; merge it with a merge commit, never squash or
  rebase.
- A fix is one commit that touches only upstream files, in upstream's
  style (`<area>: <what>`), so it applies to `master` for a merge
  request.

## From fix to phone

1. Pull request into `phosh`, merged by the owner.
2. aports' daily update check (03:41 UTC) or `scripts/update.sh art_standalone`
   opens a pull request that pins the new commit; the owner merges it.
3. aports publishes; on the phone `sudo apk upgrade`.

## Rules

The rules of the ATL fork apply here, above all "Upstream
contributions" (nothing goes upstream on the agent's initiative):
[AGENTS.md](https://github.com/nexumia-de/android_translation_layer/blob/phosh/AGENTS.md)
and
[docs/fork-workflow.md](https://github.com/nexumia-de/android_translation_layer/blob/phosh/docs/fork-workflow.md).
