# Three-minute demo: a process that improves, with people in charge

Script and storyboard for the 1.1.0 video. It is recorded from the exit demo
on a real stack with the screenshot-capture setup used for the documentation
(see `scripts/showcase/`); it uses no mock-ups. Recording is a human task. Every
claim spoken here is on the [site's claims register](../../../abada-site/claims.json).

Target length 3:00. Captions are the narration text, burned in and shipped as a
`.srt`. No music over speech; no customer names.

| Time | Screen | Narration |
| --- | --- | --- |
| 0:00–0:15 | A support ticket arrives; the process on the canvas, live. | "A support ticket comes in. An agent drafts the reply, a person checks it, and an engine holds the line between the two." |
| 0:15–0:35 | The agent step: its output contract, confidence threshold and the review it names. | "The agent can only advise. The engine checks its answer against a contract, and anything unsure goes to a person, in the same transaction." |
| 0:35–0:55 | Reviewers rejecting drafts with comments; the rework loop; cost per instance. | "People keep overruling the agent on one kind of ticket. Every decision is recorded, with its cost, without storing what must not be stored." |
| 0:55–1:20 | Insight: an override-rate finding naming the agent and its review step. | "The engine notices. Reviewers overrule this agent on a quarter of cases, and it says exactly which step." |
| 1:20–1:45 | The proposal: a small diff of the prompt and threshold. | "It proposes a small change. Nothing applies by itself." |
| 1:45–2:15 | Approve is refused: the policy requires a replay. Plan, estimate, start; an engine replica is stopped mid-run and the run still finishes. | "Before anyone can approve, the change is replayed on the last twenty real cases. The estimate is shown first. We stop an engine in the middle, and it carries on." |
| 2:15–2:40 | The replay summary: cases that would have gone differently, where; the note that this is evidence, not a verdict. | "Here is what would have been done differently, and where. It is evidence for the reviewer, not a verdict, and no running case was touched." |
| 2:40–2:55 | Approval from the diff; the new version; then Roll back in one click with a reason. | "A person approves from the diff. And if it was wrong, one click returns the previous version, with the reason on record." |
| 2:55–3:00 | Title card: Abada. Self-hosted. PostgreSQL. Open source. | "Agents advise. Rules decide. Humans approve. PostgreSQL remembers." |

## Recording checklist

- Stack: the exit-demo composition with the seeded corpus (at least 40
  finished instances of `examples/apl/review-triage.apl.yaml`) and a stub model
  so the replay is repeatable.
- Show real values only from the seeded corpus; mask nothing by editing the
  video.
- Re-record any screen that no longer matches the released UI.
- Publish the `.srt` next to the video and link both from `/investors`.
