# Governed support chat

A support desk uses DICE to remember approved policy and facts from conversations. An operator can inspect the message behind a proposed change. A reviewer can approve it. The audit trail shows which policy was in force before the approval.

The app runs locally with scripted model replies and a file-backed SQLite store. The same chat path can use a live model.

## What you’ll see

The reviewer has approved the 60-day refund window. **Current beliefs** shows the new policy, while **Before approval** still shows 30 days.

![Support desk with an approved 60-day refund policy and the earlier 30-day policy side by side](https://github.com/embabel/dice-governed-chat/releases/download/readme-screenshots/reviewed-history-desktop.png)

[See the source message before approval](https://github.com/embabel/dice-governed-chat/releases/download/readme-screenshots/source-evidence-desktop.png). The support desk can inspect the proposed wording while the 30-day rule is still in force.

<details>
<summary>Mobile view</summary>

![Mobile support desk with the current policy and the earlier belief view](https://github.com/embabel/dice-governed-chat/releases/download/readme-screenshots/reviewed-history-mobile.png)

</details>

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
| Store propositions in SQLite | `dice-starter-sqlite` auto-configuration |
| Extract claims and ground their mentions in the incoming message | `PropositionPipeline` |
| Screen extracted claims for sufficient confidence | `ConfidenceGate` |
| Read active claims within one workspace | `ScopedReads` and `ContextReadScope` |
| Choose a bounded set of facts and keep the approved policy in it | `ContextBudget` |
| Register the opt-in audit schema | `AuditLogSchema` |
| Record proposition changes and read earlier belief sets | `AuditingPropositionRepository` and `DrivineAuditLogStore` |

The SQLite starter configures the persistent proposition store from the datasource in [application.yml](src/main/resources/application.yml). [StorageConfiguration](src/main/java/example/dice/governed/StorageConfiguration.java) registers the opt-in audit schema and uses the starter’s persistence manager and Spring transaction manager. [Storage](src/main/java/example/dice/governed/Storage.java) wraps the starter’s store in `AuditingPropositionRepository`. The demo stores grounded mentions on propositions and uses their types and spans to compare claims.

The application owns source messages and pending reviews. Their tables live in the same SQLite database, so one transaction covers app rows and DICE rows. Historical belief sets include the recorded proposition fields.

## How a write commits

A chat message is handled in two phases.

1. [Workflow](src/main/java/example/dice/governed/Workflow.java) takes a scoped snapshot in a short transaction. Extraction and [AdmissionPolicy](src/main/java/example/dice/governed/AdmissionPolicy.java) then run outside the transaction, including any `DecisionService` call.
2. Commit through `audit.duringWrite`. Recheck the message ID and the active proposition revisions inside the transaction. Repeating the same ID and text returns an already-processed response. Reusing the ID for different text, or planning from facts that changed in the meantime, returns `409 Conflict`. A successful commit writes the source, reviews, proposition changes and audit records together.

Approval looks up the review by exact workspace and review ID. One transaction checks the target revision, supersedes the prior fact, stores the approved one and retires the review. If any write fails, the whole approval rolls back.

The desk reads current beliefs and pending reviews inside the same transaction. Source messages are immutable and fetched separately by workspace and ID. Historical reads require a complete baseline for the workspace. Each workspace records that baseline before it accepts writes. A time before the baseline returns unavailable.

The demo budgets each current workspace read for 200 active propositions. Approval looks up the pending review by ID and checks its target against the scoped active facts. The historical view still reconstructs the complete belief set for the requested instant.

Open an existing `data/governed-chat.sqlite` to resume the workspace and approve reviews that were already pending.

## Adapting the example

Keep the transaction boundary when adapting the workflow.

- Run model calls outside the transaction. Recheck what the plan read and commit inside one.
- Write audit records in the same transaction as the change they describe.
- Keep app-owned rows on the DICE datasource when they must change together with propositions.
- Return `409 Conflict` for a stale plan so the client can retry against current facts.

Replace the example auth and refund rules with your application’s policy.

- **Identity.** The server maps each demo token to a fixed workspace and role. [DemoAccessProperties](src/main/java/example/dice/governed/DemoAccessProperties.java) binds the server keys under `governed.demo`. The bundled browser uses the default desk and reviewer tokens. The application checks workspace membership and reviewer permissions. Integrate your identity provider and workspace membership rules before exposing an adapted application.
- **Refund classifier.** The refund workflow checks the full extracted claim for one supported workspace-wide rule, such as “The refund window is 60 days.” A recognized change can replace the pinned rule after review. A customer-specific exception or unsupported wording stays pending for clarification. A recognizable completed event, such as “Alice received a partial refund yesterday,” enters ordinary memory. Replace it with rules for your own domain.

For ordinary claims the app asks `DecisionService` through a model role to classify the claim against candidates from the same workspace. If the decision cannot identify a safe change, the proposal remains available for inspection and approval is disabled. Use the source panel on a current proposition or pending review to open its original message.

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
