# Checkpointing Is Not Chat Memory

This library persists Koog execution checkpoints.

Use it for:

- Crash recovery.
- Rollback.
- Human-in-the-loop resume.
- Long-running workflows.
- Stateless backend deployments.

Do not use it as your normal chat-history append log.

For independent HTTP requests that append user messages to a conversation, keep conversation history in Koog chat memory/history APIs or your application database. Checkpoints capture execution state at a point in time; restoring a checkpoint is not the same as appending a new chat turn.

Good session IDs:

```text
tenant:{tenantId}:support-conversation:{conversationId}
tenant:{tenantId}:workflow:{workflowId}
tenant:{tenantId}:approval:{approvalRequestId}
```

Bad session IDs:

```text
random UUID per request
pod name
process ID
```
