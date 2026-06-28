# Knowledge Base ACL Permission Control

This project adds a lightweight engineering ACL layer for knowledge-base and RAG access control. It is intentionally decoupled from a full authentication system, so it can run in local development now and later be connected to JWT or Spring Security.

## Current User Source

The current user is resolved from request metadata:

| Header | Meaning |
| --- | --- |
| `X-User-Id` | Current user id. Defaults to `anonymous` when missing. |
| `X-User-Roles` | Comma-separated roles, for example `JAVA,AI_AGENT`. |
| `X-User-Admin` | `true`, `1`, or `yes` grants admin role. |

## ACL Model

Knowledge bases support three ACL modes:

| ACL | Read permission | Manage permission |
| --- | --- | --- |
| `PUBLIC` | Everyone can read. | Owner or admin. |
| `PRIVATE` | Owner or admin only. | Owner or admin. |
| `SHARED` | Owner, admin, listed users, or listed roles. | Owner or admin. |

RAG chat sessions also store an `ownerId`. Only the session owner or admin can view, update, pin, delete, or continue the session.

## Database Fields

`KnowledgeBaseEntity` adds these fields:

| Field | Meaning |
| --- | --- |
| `ownerId` | Knowledge-base owner. |
| `acl` | `PUBLIC`, `PRIVATE`, or `SHARED`. |
| `aclUsers` | Comma-separated readable user ids. |
| `aclRoles` | Comma-separated readable roles. |

`RagChatSessionEntity` adds:

| Field | Meaning |
| --- | --- |
| `ownerId` | RAG chat session owner. |

Old rows are compatible because missing owner defaults to `anonymous` and missing ACL defaults to `PUBLIC`.

## Vector Metadata

Every vectorized chunk writes ACL metadata into pgvector document metadata:

```json
{
  "acl": "SHARED",
  "acl_owner_id": "user-1",
  "acl_read_users": "user-2,user-3",
  "acl_read_roles": "JAVA,AI_AGENT"
}
```

This prevents relying only on controller-level checks. The query flow first filters readable knowledge-base ids in the database, then filters returned vector documents again by metadata.

## Protected Paths

The ACL service is used in these paths:

- Knowledge-base upload duplicate handling
- Knowledge-base list, detail, category list, search, statistics
- Knowledge-base download, delete, category update, revectorize
- RAG query and RAG streaming query
- RAG evaluation
- RAG chat session create, list, detail, update, pin, delete, and streaming answer
- Vector search result post-filtering by metadata ACL

## Upload Examples

Private knowledge base:

```powershell
curl -X POST "http://127.0.0.1:8080/api/knowledgebase/upload" `
  -H "X-User-Id: user-1" `
  -F "file=@E:/docs/java.pdf" `
  -F "acl=PRIVATE"
```

Shared to users:

```powershell
curl -X POST "http://127.0.0.1:8080/api/knowledgebase/upload" `
  -H "X-User-Id: user-1" `
  -F "file=@E:/docs/java.pdf" `
  -F "acl=SHARED" `
  -F "aclUsers=user-2,user-3"
```

Shared to roles:

```powershell
curl -X POST "http://127.0.0.1:8080/api/knowledgebase/upload" `
  -H "X-User-Id: user-1" `
  -F "file=@E:/docs/java.pdf" `
  -F "acl=SHARED" `
  -F "aclRoles=JAVA,AI_AGENT"
```

Query as a shared role:

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://127.0.0.1:8080/api/knowledgebase/query" `
  -Headers @{ "X-User-Id" = "user-2"; "X-User-Roles" = "JAVA" } `
  -ContentType "application/json" `
  -Body '{"knowledgeBaseIds":[1],"question":"Redis Stream 在项目里怎么用？"}'
```

## Interview Version

This project does not hard-code permission checks in every controller. I extracted a unified ACL service. The request side resolves the current subject from headers now, and this can later be replaced by JWT or Spring Security. The knowledge-base table stores owner, acl, readable users, and readable roles. During vectorization, the same ACL information is written into each pgvector chunk metadata. Querying then does two checks: first filter readable knowledge-base ids before retrieval, then filter vector documents by metadata after retrieval. This avoids vector-search data leakage. RAG chat sessions also store an owner id, so session messages cannot be read or modified by another user. The design keeps authentication and authorization decoupled, while keeping the ACL decision reusable across list, download, delete, RAG query, stream chat, and evaluation flows.
