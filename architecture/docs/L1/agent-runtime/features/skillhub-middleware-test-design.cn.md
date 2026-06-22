---
level: L1
view: feature-test-guide
module: agent-runtime
feature: skillhub-middleware
status: draft
---

# agent-runtime SkillHub 中间件测试设计

本文说明 SkillHub 中间件的测试目标、样例目录、手工验证步骤和通过标准。SkillHub 的定位是渐进式加载技能说明：先暴露摘要，在需要时再加载完整 skill 定义或 skill 包。它不是 MCP tool，也不负责执行工具调用。

## 1. 测试范围

| 样例目录 | 覆盖场景 | 外部依赖 | 验证方式 |
|---|---|---|---|
| `examples/agent-runtime-middleware-skillhub-local` | 本地 `skills/` 目录作为 SkillHub | LLM API | 单进程 Spring Boot + curl |
| `examples/agent-runtime-middleware-skillhub-remote-json` | 远端 HTTP JSON catalog 作为 SkillHub | LLM API | Hub 进程 + Runtime 进程 + curl |

两个样例都需要验证三条链路：

1. summary：列出 skill 摘要，不加载完整 instructions。
2. definition：按 `skillId` 加载完整 skill。
3. package：按 `skillId` 下载 zip 包。

OpenJiuwen 相关验证点是：`SkillHubProvider` 返回的 definition metadata 中包含 `openjiuwen.skill.path`，`OpenJiuwenSkillHubInstaller` 在 Agent 执行前调用 `BaseAgent.registerSkill(...)`。

## 2. 本地 SkillHub 样例

启动：

```powershell
$env:SAA_SAMPLE_LLM_API_KEY="<your api key>"
$env:SAA_SAMPLE_OPENJIUWEN_API_BASE="https://api.deepseek.com"
$env:SAA_SAMPLE_LLM_MODEL="deepseek-chat"
$env:SERVER_PORT="19091"
Push-Location examples/agent-runtime-middleware-skillhub-local
..\..\mvnw.cmd spring-boot:run
Pop-Location
```

验证：

```bash
curl --noproxy '*' http://127.0.0.1:19091/sample/skillhub/skills
curl --noproxy '*' http://127.0.0.1:19091/sample/skillhub/skills/date-helper
curl --noproxy '*' -o date-helper.zip http://127.0.0.1:19091/sample/skillhub/skills/date-helper/package
curl --noproxy '*' -X POST http://127.0.0.1:19091/sample/skillhub/ask \
  -H 'Content-Type: application/json' \
  -d '{"stateKey":"demo-user","text":"请用日期助手告诉我今天是什么日期"}'
```

通过标准：

- summary 中包含 `date-helper`。
- definition 中包含完整 instructions，并包含 `openjiuwen.skill.path`。
- package 下载结果非空，解压后包含 `SKILL.md`。
- Agent 执行日志包含 `installed openjiuwen skill`。

## 3. 远端 JSON SkillHub 样例

终端 1 启动 Hub：

```powershell
$env:SERVER_PORT="19101"
$env:SAMPLE_SKILLHUB_ROLE="hub"
Push-Location examples/agent-runtime-middleware-skillhub-remote-json
..\..\mvnw.cmd spring-boot:run
Pop-Location
```

终端 2 启动 Runtime：

```powershell
$env:SAA_SAMPLE_LLM_API_KEY="<your api key>"
$env:SAA_SAMPLE_OPENJIUWEN_API_BASE="https://api.deepseek.com"
$env:SAA_SAMPLE_LLM_MODEL="deepseek-chat"
$env:SERVER_PORT="19102"
$env:SAMPLE_SKILLHUB_ROLE="runtime"
$env:SAMPLE_REMOTE_SKILLHUB_BASE_URL="http://127.0.0.1:19101/hub"
Push-Location examples/agent-runtime-middleware-skillhub-remote-json
..\..\mvnw.cmd spring-boot:run
Pop-Location
```

验证 Hub：

```bash
curl --noproxy '*' http://127.0.0.1:19101/hub/skills
curl --noproxy '*' http://127.0.0.1:19101/hub/skills/date-helper
curl --noproxy '*' -o date-helper-hub.zip http://127.0.0.1:19101/hub/skills/date-helper/package
```

验证 Runtime 通过远端 Hub 获取 skill：

```bash
curl --noproxy '*' http://127.0.0.1:19102/sample/skillhub/skills
curl --noproxy '*' http://127.0.0.1:19102/sample/skillhub/skills/date-helper
curl --noproxy '*' -o date-helper-runtime.zip http://127.0.0.1:19102/sample/skillhub/skills/date-helper/package
curl --noproxy '*' -X POST http://127.0.0.1:19102/sample/skillhub/ask \
  -H 'Content-Type: application/json' \
  -d '{"stateKey":"demo-user","text":"请用日期助手回答今天是什么日期"}'
```

通过标准：

- Hub 和 Runtime 看到的 skill 摘要一致。
- Runtime 不直接读取 Hub 的 `catalog.json`，只通过 `sample.remote-skillhub.base-url` 访问远端 HTTP API。
- Runtime definition 中仍包含 `openjiuwen.skill.path`，说明远端 definition 已被转换成 OpenJiuwen 可注册的本地 skill。
- Agent 执行日志包含 `installed openjiuwen skill`。

## 4. 自动化测试

```powershell
.\mvnw -pl agent-runtime verify
.\mvnw -f examples/agent-runtime-middleware-skillhub-local/pom.xml verify
.\mvnw -f examples/agent-runtime-middleware-skillhub-remote-json/pom.xml verify
```

`agent-runtime` 测试覆盖 SPI 的默认行为、OpenJiuwen installer 注册行为、自动装配条件。本地和远端 example 测试覆盖 provider 的 summary、definition、package 行为。手工 curl 步骤用于验证用户视角的进程启动、HTTP API 和 OpenJiuwen 注册链路。

## 5. 客户替换点

| 替换点 | 客户需要实现什么 | 不需要改什么 |
|---|---|---|
| 本地 skill 目录 | 放入包含 `SKILL.md` 的目录 | 不需要修改公共 SPI |
| 远端 SkillHub | 提供 summary、definition、package HTTP API | 不需要把 SkillHub 做成 MCP |
| OpenJiuwen 注册 | 在 definition metadata 中提供 `openjiuwen.skill.path` 或 `openjiuwen.skill.paths` | 不需要 override Agent 执行 |
| 企业级目录 | 可以把 JSON catalog 替换成数据库、对象存储、Nacos Skill Registry | 不需要修改 A2A executor |

## 6. 不在本轮覆盖

- Skill 的版本协商、签名校验和灰度发布。
- Skills Hub 的统一 marketplace 管理后台。
- Skill 内工具依赖的自动安装和权限审批。
- 跨租户动态鉴权和企业密钥托管。
