<!--
Draft header for the GitHub Release v1.1.0-rc.2. It goes ABOVE the existing release
body (the full release notes), which stays unchanged below the horizontal rule.
The GIF is pinned to the v1.1.0-rc.2 tag so the link never changes.
-->

<p align="center">
  <img src="https://github.com/bashizip/abada-platform/raw/v1.1.0-rc.2/docs/assets/studio-showcase.gif" alt="Abada Studio: design an agent step, deploy, a second person approves, the audit trail keeps the record" width="800">
</p>

**Agents that act — under policy.** In this release candidate, agents call tools from your
MCP servers, and the engine decides what they are allowed to do:

- 🛂 **A person approves each risky call** with its exact arguments before it runs. An approved call runs once, with the approved arguments only.
- 🧾 **Crash-safe writes.** Every model and tool call is journaled before it runs; a write interrupted by a crash is never re-sent blindly — a person confirms what happened.
- 💸 **Bounded and priced by the engine.** Turns, tokens and spend (`budget_usd`) are capped; a call with no price is *unpriced*, never free.
- 🔀 **Routing agents and delegation.** An agent picks among declared routes, and can delegate a sub-case to another governed process.
- 📡 **Live Studio.** Instances update live and the step inspector shows every model call, tool call and delegation.

REST API v1 and worker protocol v1 stay backward compatible.

### Try it

Docker is the only prerequisite:

```bash
curl -fsSL https://install.abadaplatform.com/install.sh | ABADA_VERSION=1.1.0-rc.2 bash
```

Then follow **[Tutorial: an agent that acts](https://docs.abadaplatform.com/user/tutorial-agent-that-acts/)** · [Concepts](https://docs.abadaplatform.com/concepts/) · [Use cases](https://docs.abadaplatform.com/user/use-cases/)

Questions or feedback? Join [Discussions](https://github.com/bashizip/abada-platform/discussions). Want to contribute? Start with a [good first issue](https://github.com/bashizip/abada-platform/labels/good%20first%20issue). If Abada is useful to you, a ⭐ helps others find it.

> Evaluation release candidate: certified for self-hosted Docker Compose with one or more
> engines on PostgreSQL. Public-cloud certification and an independent security review are
> not yet claimed — see *Known limitations* below.

---

<!-- existing release body follows unchanged -->
