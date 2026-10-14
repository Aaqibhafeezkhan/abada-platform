<!--
Draft header for the GitHub Release v1.1.0. It goes ABOVE the release body generated
from docs/release-notes/1.1.0-release-notes.md, separated by a horizontal rule.
The GIF link is pinned to the v1.1.0 tag; it resolves once the tag exists. Replace the GIF
with the 1.1.0 demo video (docs/assets/video/m4-demo-script.md) when it is recorded.
-->

<p align="center">
  <img src="https://github.com/bashizip/abada-platform/raw/v1.1.0/docs/assets/studio-showcase.gif" alt="Abada Studio: design an agent step, deploy, a second person approves, the audit trail keeps the record" width="800">
</p>

**Abada 1.1.0 — AI processes that improve only with your approval.**

The first release after the 1.1.0 release candidates. When people keep overruling an
agent, Abada notices, proposes a small change, shows what it would have done on real
cases, and applies it only after a person approves it:

- 📈 **Signals for reviewed agents.** Override rate, calibration gap, confidence drift and cost per case, per agent node, from at least 20 reviewed samples. No per-person statistics.
- 🔁 **Replay before approval.** Run a proposed change on the newest finished cases and see where the path would have differed. A policy can require it. Tools are not run; it is evidence, not a verdict.
- ⏪ **One-click rollback.** Version history with source diffs; roll back to any previous immutable version. Running instances stay on the version they started on.
- 🧩 **Bounded `for-each`.** A governed child process per item of a list, with `max_items` and `max_parallel`.
- ✍️ **Schema-driven authoring.** Refinements are applied as validated patches, never a silent rewrite.

It builds on the agents that act from `1.1.0-rc.2`: MCP tools under approval policies, crash-safe writes and engine-priced cost. REST API v1 and worker protocol v1 stay backward compatible.

### Try it

Docker is the only prerequisite:

```bash
curl -fsSL https://install.abadaplatform.com/install.sh | bash
```

Upgrading? Read [Upgrading to 1.1.0](https://github.com/bashizip/abada-platform/blob/v1.1.0/docs/operations/upgrading.md#upgrading-to-110-v34v38-governed-improvement) first: back up and keep `ABADA_ENCRYPTION_KEY`. A tool name without a server is now refused at deployment.

[Concepts](https://docs.abadaplatform.com/concepts/) · [Tutorial: an agent that acts](https://docs.abadaplatform.com/user/tutorial-agent-that-acts/) · [Use cases](https://docs.abadaplatform.com/user/use-cases/) · [Pilot runbook](https://github.com/bashizip/abada-platform/blob/v1.1.0/docs/operations/pilot-runbook.md)

Questions or feedback? Join [Discussions](https://github.com/bashizip/abada-platform/discussions). Want to contribute? Start with a [good first issue](https://github.com/bashizip/abada-platform/labels/good%20first%20issue). If Abada is useful to you, a ⭐ helps others find it.

> 1.1.0 is an evaluation release: certified for self-hosted Docker Compose with one or more
> engines on PostgreSQL. Public-cloud certification and an independent security review have
> not been done. See *Known limitations* below.

---

<!-- release body from docs/release-notes/1.1.0-release-notes.md follows -->
