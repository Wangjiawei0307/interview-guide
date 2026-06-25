# 本地启动停止命令

> 项目目录：`E:\workspace\idea_workspace\interview-guide-master`

## 1. 启动项目

打开 PowerShell，执行：

```powershell
cd E:\workspace\idea_workspace\interview-guide-master
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\scripts\start-dev.ps1
```

启动成功后访问：

```text
前端页面：http://127.0.0.1:5173/
后端接口：http://127.0.0.1:8080/
接口文档：http://127.0.0.1:8080/v3/api-docs
健康检查：http://127.0.0.1:8080/api/resumes/health
RustFS 控制台：http://127.0.0.1:9001
```

RustFS 登录信息：

```text
账号：rustfsadmin
密码：rustfsadmin
bucket：interview-guide
```

## 2. 停止项目

```powershell
cd E:\workspace\idea_workspace\interview-guide-master
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\scripts\stop-dev.ps1
```

这个命令会停止：

```text
前端 Vite：5173
后端 Spring Boot：8080
Docker 里的 PostgreSQL / RustFS / Redis
```

## 3. 如果需要同时启动 Docker Redis

默认情况下，如果你本机已有 Redis 占用 `6379`，脚本不会再启动 Docker Redis。

如果你明确想用 Docker Redis：

```powershell
cd E:\workspace\idea_workspace\interview-guide-master
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\scripts\start-dev.ps1 -WithRedis
```

如果端口冲突，先停掉本机 Redis 或不要加 `-WithRedis`。

## 4. 查看运行状态

查看 Docker 容器：

```powershell
cd E:\workspace\idea_workspace\interview-guide-master
$env:Path="C:\Program Files\Docker\Docker\resources\bin;$env:Path"
docker compose -p interview-guide-master -f docker-compose.dev.yml ps
```

检查端口：

```powershell
Test-NetConnection 127.0.0.1 -Port 5173
Test-NetConnection 127.0.0.1 -Port 8080
Test-NetConnection 127.0.0.1 -Port 5432
Test-NetConnection 127.0.0.1 -Port 9000
```

检查后端：

```powershell
Invoke-WebRequest -Uri "http://127.0.0.1:8080/api/resumes/health" -UseBasicParsing
```

## 5. 查看日志

后端日志：

```powershell
Get-Content E:\workspace\idea_workspace\interview-guide-master\tmp\backend-full.out.log -Tail 100
Get-Content E:\workspace\idea_workspace\interview-guide-master\tmp\backend-full.err.log -Tail 100
```

前端日志：

```powershell
Get-Content E:\workspace\idea_workspace\interview-guide-master\tmp\frontend-vite.out.log -Tail 100
Get-Content E:\workspace\idea_workspace\interview-guide-master\tmp\frontend-vite.err.log -Tail 100
```

Docker 日志：

```powershell
$env:Path="C:\Program Files\Docker\Docker\resources\bin;$env:Path"
docker logs --tail 100 interview-postgres
docker logs --tail 100 interview-rustfs
```

## 6. 查看上传文件和数据库内容

查看 RustFS 里真实上传的文件：

```text
浏览器打开：http://127.0.0.1:9001
账号：rustfsadmin
密码：rustfsadmin
进入 bucket：interview-guide
```

查看数据库记录：

```powershell
$env:Path="C:\Program Files\Docker\Docker\resources\bin;$env:Path"
docker exec -it interview-postgres psql -U postgres -d interview_guide
```

进入 `psql` 后查询简历：

```sql
select id, original_filename, storage_key, analyze_status, uploaded_at
from resumes
order by id desc;
```

查询知识库：

```sql
select id, name, original_filename, storage_key, vector_status, uploaded_at
from knowledge_bases
order by id desc;
```

退出数据库：

```sql
\q
```

## 7. 修改 AI Key

打开项目根目录的 `.env`，把：

```text
AI_BAILIAN_API_KEY=test-api-key
```

改成你的真实 DashScope API Key。

改完 `.env` 后需要重启后端：

```powershell
cd E:\workspace\idea_workspace\interview-guide-master
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\scripts\stop-dev.ps1
.\scripts\start-dev.ps1
```

## 8. 常见问题

如果打开 `http://127.0.0.1:8080/` 显示：

```json
{"code":404,"message":"API 接口不存在"}
```

这是正常的。`8080` 是后端接口，不是前端页面。页面要打开：

```text
http://127.0.0.1:5173/
```

如果提示脚本无法运行：

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
```

如果 Docker 命令找不到：

```powershell
$env:Path="C:\Program Files\Docker\Docker\resources\bin;$env:Path"
docker version
```

如果 Docker 引擎没启动，先手动打开 Docker Desktop，等左下角显示：

```text
Engine running
```

然后再执行启动脚本。

如果上传失败并且日志里出现 CORS：

确认 `.env` 里有：

```text
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,http://localhost:5174,http://127.0.0.1:5174,http://localhost:80,http://127.0.0.1:80
```

如果 AI 分析失败：

大概率是 `.env` 里的 `AI_BAILIAN_API_KEY` 还是占位值，需要换成真实 API Key。

## 9. 手动启动命令备用

一般不需要手动启动，优先用 `scripts/start-dev.ps1`。

如果脚本不用，手动启动依赖：

```powershell
cd E:\workspace\idea_workspace\interview-guide-master
$env:Path="C:\Program Files\Docker\Docker\resources\bin;$env:Path"
docker compose -p interview-guide-master -f docker-compose.dev.yml up -d postgres rustfs
```

手动启动后端：

```powershell
cd E:\workspace\idea_workspace\interview-guide-master
$env:JAVA_HOME="C:\Users\ASUS\.vscode\extensions\redhat.java-1.54.0-win32-x64\jre\21.0.10-win32-x86_64"
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:bootRun
```

手动启动前端：

```powershell
cd E:\workspace\idea_workspace\interview-guide-master\frontend
pnpm.cmd exec vite --host 127.0.0.1
```
