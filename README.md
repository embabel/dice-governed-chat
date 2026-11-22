# Governed support chat

A support desk uses DICE to remember approved policy and facts from conversations. An operator can inspect the message behind a proposed change. A reviewer can approve it. The audit trail shows which policy was in force before the approval.

The app runs locally with scripted model replies and a file-backed SQLite store. The same chat path can use a live model.

## Run locally

Use Java 21, Bun, and Node.js 22.12 or later. The server resolves DICE `0.5.0-SNAPSHOT` from the Embabel snapshot repository and Embabel Agent `1.5.4`. The app uses the published DICE snapshot because it needs the scoped store and durable history APIs.

Start the server.

```sh
./mvnw -B spring-boot:run
```

Start the web app in a second terminal.

```sh
cd web
bun install --frozen-lockfile
bun run dev --host 127.0.0.1
```

Open [the support desk](http://127.0.0.1:5173). The server listens on loopback. To use a different server port, set `DICE_API_URL` when starting Vite.

## Follow a policy change

1. As **Support desk**, send “Support leads can approve refunds within 60 days.” The claim goes to review. The pinned 30-day policy remains current.
2. Open the proposed change's source to read the original message.
3. Switch to **Reviewer** and approve the change. The 60-day policy becomes current. Its source still points to the original support message.
4. Select **Before approval** in the history view. It shows the earlier policy. A time before the baseline returns unavailable.
5. Restart the server with the same database and reopen the support desk. The policy and history remain available.

Try “Email is our preferred follow-up channel.” followed by “Phone is our preferred follow-up channel.” The second claim gives Embabel Decision an existing fact to compare. Protected policy follows the approval workflow.

## What DICE does

| What the app needs | DICE API |
| --- | --- |
| Extract claims and resolve mentions from the incoming message | `PropositionPipeline` |
| Screen extracted claims for sufficient confidence | `ConfidenceGate` |
| Read active claims within one workspace | `ScopedReads` and `ContextReadScope` |
| Choose a bounded set of facts and keep the approved policy in it | `ContextBudget` |
| Record changes and read earlier belief sets | `AuditingPropositionRepository` and `DrivineAuditLogStore` |

The application owns workspace authorization and reviewer permissions. It asks Embabel's `DecisionService` through a model role to classify ordinary claims against candidates from the same workspace. If the decision cannot identify a safe change, the proposal remains available for inspection and approval is disabled. The application stores review requests and source messages in SQLite with the same write boundary as the audited changes. Historical belief sets include the recorded proposition fields. Use the source panel on a current proposition or pending review to open its original message.

The refund workflow checks the full extracted claim for a supported workspace-wide rule, such as “The refund window is 60 days.” A recognized change can replace the pinned rule after review. A customer-specific exception or unsupported wording stays pending for clarification. A recognizable completed event, such as “Alice received a partial refund yesterday,” enters ordinary memory.

Each workspace establishes a complete baseline before it accepts writes. Later historical reads are served only within verified audit coverage. The app shows a local demo identity for each role. Integrate your identity provider and workspace membership rules before exposing an adapted application.

The demo budgets each current workspace read for 200 active propositions. Approval looks up a matching archived claim by scope and exact text, so accumulated history does not count against that read budget. The historical view still reconstructs the complete belief set for the requested instant.

## Live models

Set `OPENAI_API_KEY` in your environment and run the server with the live profile.

```sh
./mvnw -B spring-boot:run -Dspring-boot.run.profiles=live
```

The scripted profile uses `data/governed-chat.sqlite`. The live profile uses `data/governed-chat-live.sqlite`. Set `GOVERNED_CHAT_DB` to override the path. Keep provider credentials outside the repository.

| Setting | Default in the live profile | Use |
| --- | --- | --- |
| `GOVERNED_CHAT_MODEL` | `gpt-4.1-mini` | Extract claims from chat text. |
| `GOVERNED_CHAT_DECISION_MODEL` | `gpt-4.1-mini` | Answer the typed questions for the `dice-revision` role. |
| `GOVERNED_CHAT_EMBEDDING_MODEL` | `text-embedding-3-small` | Supply embeddings to the DICE store. |

## Verify

Install Chromium for Playwright once from the `web` directory.

```sh
cd web
bun install --frozen-lockfile
bunx playwright install chromium
```

Run `./scripts/verify` from the repository root. It builds the server and web app, then starts the packaged server with a temporary database and runs the browser progression. The Java progression test rejects cross-workspace reads, reopens the database with a pending review, and checks the current and earlier policy after approval.
