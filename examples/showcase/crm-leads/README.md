# CRM showcase: lead to opportunity with Twenty

An end-to-end Abada process around a real, self-hosted CRM. An inbound sales
lead is researched by an AI agent that uses the CRM through MCP tools,
qualified by deterministic rules, reviewed by a person in a custom form, and
turned into an opportunity in the CRM by an external worker.

```text
 inbound lead ──▶ research ──▶ qualify ──▶ route ──▶ sales-review ──approve──▶ create-opportunity ──▶ won
                  (agent +     (decision    │        (form, group     │         (crm-worker,
                   MCP tools)   table)      │         sales)          │          Java SDK)
                                            └──nurture──▶ nurtured    └──reject──▶ declined
```

Each step shows one part of the doctrine:

| Step | Node | What it shows |
| --- | --- | --- |
| `research` | `agent` | **Agents advise.** The model runs in the agent worker, outside the engine transaction. It may call three CRM tools and nothing else; the engine checks its answer against an output schema and a confidence threshold. |
| `qualify` | `decision-table` | **Rules decide.** The same score always gives the same lane, evaluated in the engine transaction and audited. |
| `sales-review` | `human-input` + form | **Humans approve.** A person in group `sales` sees the agent's brief and a form pre-filled by the rules, then approves or rejects with a comment. |
| `create-opportunity` | `engine-task` | **Workers act.** A plain external worker (Abada Java SDK) writes to the CRM and reports back. |

Everything lives in PostgreSQL: the instance, the agent's journaled model and
tool calls, the decision, the review and the worker's result.

## Why Twenty

[Twenty](https://twenty.com) (AGPL-3.0) is an open-source CRM with an official
Docker image, a REST and GraphQL API generated from its data model, and a demo
seed with about 600 real-world companies and 150 opportunities. NextCRM was
the other candidate; it has no comparable published image or stable API, so
Twenty makes a more realistic and more reproducible showcase.

## What runs

```text
                  ┌──────────── Abada dev stack ────────────┐
 Studio ─────────▶│ engine ◀── agent worker ──MCP──▶ crm-mcp ├──REST──▶ Twenty
 (alice)          │   ▲                                      │            ▲
                  │   └──────── crm-worker ──────────────────┼──REST──────┘
                  └──────────────────────────────────────────┘
```

| Container | Source | Role |
| --- | --- | --- |
| `twenty-server`, `twenty-worker`, `twenty-db`, `twenty-redis` | `twentycrm/twenty:v2.45.8` | The CRM, at <http://crm.localhost> |
| `crm-mcp` | [`mcp/crm_mcp.py`](mcp/crm_mcp.py) | A narrow MCP server over the Twenty REST API: `find_company`, `list_opportunities`, `add_note`. It holds the Twenty API key. |
| `crm-worker` | [`crm-worker/CrmWorker.java`](crm-worker/CrmWorker.java) | External worker for topic `crm.create-opportunity` |
| `abada-engine`, `abada-agent-worker` | the dev stack | Recreated with two settings from [`compose.yaml`](compose.yaml): the dev engine accepts a plain-http tool server, and the agent worker may reach the `crm` server |

## Run it

Prerequisites: the Abada dev stack (`./scripts/dev/up.sh`) with an AI provider
for Gemini (Studio > Settings > AI Providers, or `ABADA_LLM_GEMINI_API_KEY` in
`.env.dev`), Docker with about 3 GB of free memory, and `python3`.

```bash
./examples/showcase/crm-leads/showcase.sh up
```

The first run pulls Twenty, seeds the demo CRM and builds the worker (a few
minutes). It is safe to run again. It:

1. generates the secrets in `examples/showcase/crm-leads/.env` (git-ignored);
2. starts Twenty and recreates the engine and agent worker with the showcase settings;
3. seeds the CRM and creates a Twenty API key;
4. in Keycloak, adds `alice` to a new `sales` group and creates the `crm-worker`
   client (a service account in `abada-worker`);
5. starts `crm-mcp` and `crm-worker`;
6. through the Abada API, as `alice`: creates the project **CRM showcase**, adds
   the `crm` tool server, stores its tool credential, adds the form and deploys
   the process.

Then send a lead:

```bash
./examples/showcase/crm-leads/showcase.sh lead mongodb
```

| Lead | In the CRM | Expected path |
| --- | --- | --- |
| `mongodb` | yes, no opportunity yet | high score, new logo → review with stage `SCREENING` |
| `zendesk` | yes, with won and open deals | existing customer → review with stage `MEETING` |
| `bakery` | no, vague message | low score → `nurtured`; nobody is asked |

Follow it:

1. **Studio** (<http://studio.localhost>, `alice` / `alice`; sign out and in
   once so your token carries the new `sales` group). Open the project **CRM
   showcase** > Operations and the instance: the agent's steps (each model
   and tool call, with tokens), the `DECISION_TABLE_APPLIED` record, the
   variables.
2. **Twenty** (<http://crm.localhost>, `tim@apple.dev` / `tim@apple.dev`):
   Companies > MongoDB > Notes shows the agent's research note.
3. **Tasks** in Studio: open *Qualify the lead*, claim it, adjust the stage,
   amount and notes in the form, then **Approve**.
4. Back in Twenty, Opportunities shows *MongoDB - inbound lead …* with the
   stage and amount from the form and a note with the agent's summary and your
   notes. The instance ends in `won` with `opportunity_url` set:

```bash
./examples/showcase/crm-leads/showcase.sh status
```

## How the pieces fit

### The process

[`process/lead-to-opportunity.apl.yaml`](process/lead-to-opportunity.apl.yaml)
is the whole contract. Read it top to bottom; every node is commented.

### Agent and MCP tools

The agent node binds three tools by reference:

```yaml
tools:
  - crm/find_company
  - crm/list_opportunities
  - crm/add_note
```

`crm` is a project **tool server** ([`tool-servers/crm.yaml`](tool-servers/crm.yaml)):
the MCP endpoint, the name of the tool credential, and a policy per tool.
`find_company` and `list_opportunities` are `read`; `add_note` is a `write`
with `idempotency: key`. At deployment the engine resolves the references and
freezes them into the process version, so editing the tool server later never
changes a running instance.

Four checks keep the agent inside its lane:

- **Operator allow-list.** The agent worker only reaches servers listed in
  `ABADA_AGENT_ALLOWED_TOOLS` (`crm` here).
- **Bound tools only.** The model is offered `crm__find_company`,
  `crm__list_opportunities` and `crm__add_note`. There is no tool to create
  an opportunity, so the agent cannot create one, whatever the lead's message
  says.
- **Credentials stay out of the process.** `crm-mcp` requires a bearer token.
  Abada stores it encrypted as the project tool credential `crm-mcp-token` and
  hands it only to the worker holding the task's lease. The Twenty API key
  never leaves `crm-mcp`.
- **Journaled writes.** Every model and tool call is recorded before and
  after it runs. `add_note` is sent with the engine's per-step
  `Idempotency-Key`, and `crm-mcp` returns the note it already wrote for a
  key it has seen, so a retried attempt does not write the note twice.

The engine then checks the answer: `research` must match the `output_schema`
and carry a `_confidence` of at least 70. A weak or malformed answer goes to
`sales-review` instead of deciding anything (`on_low_confidence`,
`on_invalid_output`). When `gemini-3.6-flash` is rate limited or overloaded,
the worker tries the `fallback_models` in the same lease; it never switches
because of what a model answered.

### Rules

`qualify` maps the agent's advice to a lane and a proposed pipeline stage:
existing customers go to review at `MEETING`, a score of 60 or more to review
at `SCREENING`, anything else to nurturing. `route` follows the lane.

### The form

[`forms/lead-qualification.json`](forms/lead-qualification.json) is a FORM
resource in the project's `forms` folder; `formKey: lead-qualification`
selects it. Field ids are process variable names: `deal_stage` arrives
pre-filled with the table's proposal, and whatever the reviewer submits
(`deal_stage`, `deal_amount`, `review_notes`) is written back with the
decision. `outcomes` gives the reviewer two buttons; `reject` requires a
comment.

### The worker

[`CrmWorker.java`](crm-worker/CrmWorker.java) is about 200 lines with the
Abada Java SDK (`sdk/java`): register the topic, `fetchAndLock`, do the work,
`complete` with variables, or `fail` with a retry count. Delivery is
at-least-once, so the worker is idempotent: it names the opportunity after
the process instance and reuses one that already exists. It runs as a
single-file Java program, so you can edit it and rebuild with
`./showcase.sh compose up -d --build crm-worker`.

## Things to try

- **Reject** a review with a comment: the instance ends in `declined`, and the
  comment is in `sales_review_comment`.
- **Stop the worker** (`./showcase.sh compose stop crm-worker`), approve a
  review: the task waits durably in the engine. Start the worker again and it
  completes.
- **Break the CRM** (`./showcase.sh compose stop twenty-server`) while a
  worker task runs: the worker reports a failure, the engine retries after
  15 seconds, then opens an incident you can retry from Operations once
  Twenty is back.
- **Change the rules**: edit the table in Studio (raise the threshold to 80)
  and deploy. New instances use the new version; running ones keep theirs.
- **Inspect evidence**: the agent steps of an instance show which model
  answered, every tool call, the tokens and the cost, without prompts or
  payloads leaking into logs.

## Commands

```text
./showcase.sh up              start and configure everything (idempotent)
./showcase.sh lead <name>     mongodb | zendesk | bakery
./showcase.sh status          latest instances, lane and opportunity link
./showcase.sh logs            follow crm-mcp, crm-worker and the agent worker
./showcase.sh compose <args>  docker compose with the showcase files
./showcase.sh down            stop the showcase containers
./showcase.sh reset           remove the CRM data and the generated .env
```

`down` and `reset` leave the engine and agent worker with the showcase
settings; run `./scripts/dev/up.sh` to return them to the plain dev
configuration.

## Limits

This is a development showcase, not a deployment recipe:

- The engine accepts the plain-http tool server only because the overlay sets
  `ABADA_TOOLS_ALLOWINSECUREHTTP`. Production tool servers use https.
- The CRM seed and the API key come from Twenty's development commands.
- `crm-mcp` keeps idempotency keys in memory: a retried `add_note` is
  deduplicated while the container runs, not across its restarts.
- The agent's output depends on the model. The rules, the form and the
  worker are deterministic; the score and the summary are not.
