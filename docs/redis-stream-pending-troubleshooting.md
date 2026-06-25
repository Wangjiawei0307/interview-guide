# 语音面试一直等待评估的排查记录

## 这次真正的根因

这次的根因不是“本地 Redis 和 Docker Redis 冲突”本身，而是：

**WebSocket 断开后的自动结束路径只把数据库状态改成了 `PENDING`，没有把评估任务写入 Redis Stream。**

代码里原来有两条结束会话路径：

```text
正常结束：
VoiceInterviewService.endSession(String)
  -> endSession(session)
  -> voiceEvaluateStreamProducer.sendEvaluateTask(sessionId)

WebSocket 断开兜底结束：
VoiceInterviewService.endSessionIfInProgress(String)
  -> endSession(session)
```

问题出在第二条路径。它只调用了内部的 `endSession(session)`，而这个内部方法只会：

```text
1. 把 session 状态改成 COMPLETED
2. 把 evaluate_status 改成 PENDING
3. 保存数据库
```

但它不会发送：

```text
voice:evaluate:stream
```

所以最后形成了这个悬挂状态：

```text
数据库：evaluate_status = PENDING
Redis Stream：没有对应 voiceSessionId
后端消费者：没有任务可消费
前端页面：一直轮询 PENDING
```

## 为什么当时又看到 Redis 混淆

排查时还有一个干扰项：后端实际连接的是宿主机 Redis：

```text
127.0.0.1:6379
```

而 Docker Desktop 里也有一个 Redis 容器。两个 Redis 不是同一个实例。

所以如果只看 Docker Desktop 里的 Redis，会误判队列状态。正确做法是先确认后端到底连的是哪个 Redis，再查那个 Redis 的 Stream。

## 已修复内容

已在 `VoiceInterviewService` 中修复：

```text
app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java
```

修复点：

1. `endSessionIfInProgress()` 自动结束会话后，也会触发评估入队。
2. `cleanupStaleSessions()` 清理超时会话后，也会触发评估入队。
3. Redis Stream 入队改为事务提交后执行，避免数据库还没提交、消费者已经拿到任务的竞态。

修复后的核心逻辑是：

```text
endSession(session)
enqueueEvaluationAfterCommit(session.getId())
```

也就是说，无论是正常结束，还是 WebSocket 断开兜底结束，只要会话被结束，就会在事务提交后写入 Redis Stream 评估任务。

## 下次如何快速定位

### 1. 看接口状态

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/voice-interview/sessions/8/evaluation"
```

如果一直是：

```json
{
  "evaluateStatus": "PENDING",
  "evaluateError": null,
  "evaluation": null
}
```

说明前端在轮询，但后端不一定真的在评估。

### 2. 查数据库

```powershell
& "C:\Program Files\Docker\Docker\resources\bin\docker.exe" exec interview-postgres `
  psql -U postgres -d interview_guide `
  -c "select id, role_type, status, current_phase, evaluate_status, evaluate_error, updated_at from voice_interview_sessions where id=8;"
```

再查评估结果表：

```powershell
& "C:\Program Files\Docker\Docker\resources\bin\docker.exe" exec interview-postgres `
  psql -U postgres -d interview_guide `
  -c "select id, session_id, overall_score, created_at from voice_interview_evaluations where session_id=8;"
```

如果会话是 `COMPLETED`，评估状态是 `PENDING`，但结果表没有记录，就继续查 Redis。

### 3. 确认后端连接的是哪个 Redis

```powershell
Get-NetTCPConnection -LocalPort 6379 -State Listen |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

再看进程：

```powershell
Get-Process -Id (Get-NetTCPConnection -LocalPort 6379 -State Listen |
  Select-Object -ExpandProperty OwningProcess -Unique) |
  Select-Object Id,ProcessName,Path
```

如果看到：

```text
ProcessName = redis-server
```

说明后端连的是本机 Redis，不是 Docker Redis。

### 4. 查真实 Redis Stream

后端连本机 Redis 时，用本机 `redis-cli`：

```powershell
redis-cli XLEN voice:evaluate:stream
redis-cli XINFO GROUPS voice:evaluate:stream
redis-cli XRANGE voice:evaluate:stream - + COUNT 20
redis-cli XPENDING voice:evaluate:stream voice-evaluate-group
```

重点看 `XRANGE` 里有没有目标 session：

```text
voiceSessionId
8
```

如果数据库是 `PENDING`，但 Stream 里没有 `voiceSessionId=8`，说明任务没有入队。

### 5. 查后端是否已经消费

```powershell
Select-String -Path .\tmp\backend-restart.out.log `
  -Pattern "Processing 语音面试评估 task|voiceSessionId=8|task completed|task failed|sessionId=8" |
  Select-Object -Last 80
```

如果看到：

```text
Processing 语音面试评估 task: payload=voiceSessionId=8
```

说明后端消费者已经拿到任务。

## 临时补救方式

如果线上或本地历史数据已经卡住，数据库是 `PENDING`，但 Redis Stream 里没有对应 session，可以手动补发：

```powershell
redis-cli XADD voice:evaluate:stream "*" voiceSessionId 8 retryCount 0
```

然后再查接口：

```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/voice-interview/sessions/8/evaluation"
```

正常会从：

```text
PENDING
```

变成：

```text
PROCESSING
```

## 一句话记忆

如果语音面试一直显示“等待评估”，不要只看页面。先查数据库状态，再查后端真实连接的 Redis Stream 里有没有对应的 `voiceSessionId`。如果数据库是 `PENDING` 但 Stream 没消息，就是“状态已写、任务未入队”。
