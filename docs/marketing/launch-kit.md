# Launch kit

Drafts to adapt, not paste blindly. Re-check every claim against the current release notes
before posting. Items marked **[verify]** must be confirmed or removed.

## Positioning

**One line:** Abada is the open-source, self-hosted runtime where AI agents advise, rules
decide and humans approve — with PostgreSQL as the record of everything.

**Tagline:** Agents advise. Rules decide. Humans approve. PostgreSQL remembers.

**The problem:** Teams want AI inside real business processes (refunds, KYC, lead
triage, claims), but a model answer that writes straight to a system of record is not
acceptable in production. Agent frameworks run the model; they do not decide what the
answer is allowed to change, who approves it, or what the auditor sees afterwards.

**What Abada does differently (each backed by docs):**

| Claim | Evidence |
| --- | --- |
| The model runs outside the database transaction; its result enters state only through a validating engine command | `docs/architecture/runtime-state.md` |
| Output schema and confidence threshold are enforced by the engine; failures route to a person | `docs/reference/apl-node-reference.md` |
| MCP tools under read / write / approval-required policies; a person approves risky calls with exact arguments | `docs/release-notes/1.1.0-rc.2-release-notes.md` |
| Turns, tokens and spend are bounded and priced by the engine | same |
| Rules are sandboxed CEL, rejected at deployment when unsafe | `docs/reference/bpmn-support.md` |
| Processes are YAML (APL) you review in a pull request; BPMN 2.0 and Camunda 7 definitions import | `docs/reference/apl-specification.md`, docs "BPMN import and compatibility" |
| Self-hosted: your PostgreSQL, your OIDC, your model endpoint (any OpenAI-compatible gateway), telemetry off by default | `docs/reference/agent-worker.md`, README |
| AGPL-3.0, no enterprise edition; worker SDK Apache-2.0 | `LICENSE`, `sdk/java/LICENSE` |

**Audiences and hooks**

| Audience | Hook | Where they are |
| --- | --- | --- |
| Java/Spring devs, Camunda 7 / Flowable users | A self-hosted, PostgreSQL-only engine that imports your Camunda 7 BPMN and adds governed AI | r/java, r/SpringBoot, JUGs, Baeldung/Java Weekly, Devoxx |
| AI / agent engineers | Guardrails that live in the engine, not the prompt | r/LocalLLaMA, r/AI_Agents, AI Engineer meetups, HN |
| Platform / DevOps, self-hosters | One command, Docker only, your infra, no licence server | r/selfhosted, selfh.st, awesome-selfhosted |
| Architects in regulated industries | Audit trail of every model call, human approval, sovereignty | LinkedIn, banking/insurance tech meetups |
| African tech ecosystem | Sovereign, self-hosted AI built by an African engineer **[verify framing with maintainer]** | GDG, DevFest, ALX/Andela communities |

## Show HN

**Title (≤ 80 chars):**
`Show HN: Abada – self-hosted runtime where AI agents advise and humans approve`

**Body:**

> Hi HN, I'm Patrick. I built Abada because every "AI in the business process" demo I saw
> let a model answer write directly to a system of record. That is fine for a demo and
> unacceptable for refunds, KYC or claims.
>
> Abada is a process engine (Java 21, Spring Boot, PostgreSQL only) where:
>
> - the model call runs outside the database transaction, as durable external work;
> - the engine — not the model, the worker or the UI — validates the answer against a
>   declared JSON schema and confidence threshold, and routes weak or malformed answers
>   to a person;
> - agents can use tools from your MCP servers under read / write / approval-required
>   policies; a person approves each risky call with its exact arguments, and a crash
>   never blindly repeats a write;
> - conditions and decision tables are sandboxed CEL; nothing about routing is guessed.
>
> Processes are written in YAML (we call it APL) so they can be reviewed in a pull request;
> BPMN 2.0 and Camunda 7 definitions can be imported.
>
> Try it with Docker: `curl -fsSL https://install.abadaplatform.com/install.sh | bash`
> (or clone the repo and run `./release/abada-platform up dev`).
>
> It is AGPL-3.0 with no enterprise edition; the worker SDK is Apache-2.0. It is a release
> candidate: the certified topology is self-hosted Compose with one or more engines on
> PostgreSQL, and there has been no independent security review yet.
>
> I would love feedback on the APL format and on the agent governance model — what would
> you need before letting an agent touch production data?
>
> Repo: https://github.com/bashizip/abada-platform · Docs: https://docs.abadaplatform.com

**Rules for the day:** post Tue–Thu, 8–9 am US Eastern. Do not ask anyone to upvote (HN
penalises it). Reply to every comment within the first 3 hours, technically and without
defensiveness. Have the FAQ below open.

## Reddit

- **r/selfhosted** — title: "Abada: self-hosted process engine for AI workflows with
  human approval (Docker, PostgreSQL, OIDC)". Lead with the install command, screenshots,
  resource needs **[verify: measure RAM/CPU of the dev stack]**, and "telemetry off by default".
- **r/java / r/SpringBoot** — lead with the engineering: transactional outbox, durable
  leases, PostgreSQL Testcontainers as the correctness reference, Camunda 7 import.
  Link the cornerstone post, not the homepage.
- **r/LocalLLaMA** — "Put a local model behind schema validation and human approval".
  Only post after testing an Ollama or vLLM OpenAI-compatible endpoint end to end **[verify]**.

Reddit etiquette: one subreddit per day, answer comments, read each subreddit's
self-promotion rule first, and be a participant there before and after.

## Product Hunt

- Name: Abada · Tagline: "Governed AI business processes on your own infrastructure"
- Gallery: showcase GIF, Studio designer, approval task, audit trail, APL YAML.
- First comment: the "why" paragraph of the Show HN post.

## Newsletter pitch (email or submission form)

> Subject: Open-source runtime for governed AI business processes (self-hosted, Java)
>
> Hi <name>, Abada is an AGPL, self-hosted process engine where AI agents advise, rules
> decide and humans approve. The engine validates every model answer against a schema and
> confidence threshold before it can change process state, and agents' MCP tool calls run
> under approval policies. Java 21 / Spring Boot / PostgreSQL; BPMN and Camunda 7 import.
> Might fit <newsletter section>. Repo: https://github.com/bashizip/abada-platform —
> 2-minute install: https://docs.abadaplatform.com/user/quickstart/ . Thanks, Patrick

Targets: Java Weekly (Baeldung), This Week in Spring, JVM Weekly, Console.dev,
Changelog News, TLDR (AI / DevOps), Self-Host Weekly (selfh.st), DevOps'ish, AI newsletters.

## Objections FAQ (keep open during launch)

| Question | Honest answer |
| --- | --- |
| Why AGPL? | So improvements to a hosted Abada stay open; there is no enterprise edition held back. Workers you write against the SDK are Apache-2.0, so your business code is not affected by the AGPL. Releases up to `1.0.0-rc.5` were MIT. |
| Why not Temporal? | Temporal is a durable code-execution platform; Abada is a process runtime with human tasks, BPMN import, decision tables and an engine-enforced contract for AI output. Different layer — see `comparison-draft.md`. |
| Why not n8n / Zapier? | Those are integration automation tools. Abada targets processes that need audit, human approval and transactional state in your own PostgreSQL. |
| Why not just LangGraph? | LangGraph orchestrates the model; Abada decides what the model's answer is allowed to change, persists the process durably and puts people in the loop. They can be combined: a LangGraph agent can be an external worker. **[verify example exists before saying so]** |
| Is it production ready? | It is a release candidate. Certified: self-hosted Docker Compose, one or more engines on PostgreSQL. Not yet: public-cloud certification, independent security review. |
| Single maintainer? | Yes today. That is why contributors are wanted; the test suite, gate reports and AGENTS.md exist so others can contribute safely. |
| Which models? | Gemini, OpenAI, Anthropic, DeepSeek, OpenRouter or any OpenAI-compatible gateway. |
| Resource needs? | **[verify: measure the dev stack with `docker stats`]** |

## Talk abstract

**Title:** Governing AI agents with a durable state machine

**Abstract (≈150 words):** An LLM is a probabilistic advisor; a business process is a
deterministic, auditable commitment. Wiring one directly into the other is how agent
demos become production incidents. This talk shows an architecture that keeps them
apart: the model runs outside the database transaction as durable external work, and its
answer enters process state only through an engine command that validates it against a
declared schema and confidence threshold. Weak answers go to people; risky tool calls wait
for human approval with their exact arguments; decision rules stay in sandboxed CEL;
PostgreSQL holds every attempt as evidence. We will walk through the transactional outbox,
lease-based work acquisition, crash-safe tool writes and a live demo with the open-source
Abada runtime — and discuss what we got wrong along the way.

**Formats:** 45-min talk, 20-min short version, 3-hour hands-on lab (install → agent
process → approval → audit).
