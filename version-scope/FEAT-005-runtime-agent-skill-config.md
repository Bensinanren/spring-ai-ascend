---
version: 0715
module: agent-runtime
feature_type: functional
feature_id: FEAT-005
status: active
related_docs:
  - ../architecture/L0-Top-Level-Design/boundaries.md
  - ../architecture/L0-Top-Level-Design/glossary.md
  - ../architecture/L1-High-Level-Design/agent-runtime/README.md
  - ../architecture/L1-High-Level-Design/agent-runtime/development.md
---

# 智能体中间件请求代理：Skill Hub 管理

## 1. 特性定位

FEAT-005 定义 `agent-runtime` 的 Skill Hub 管理需求。Runtime 负责 Skill Hub 连接认证与访问控制、安全访问、skill 下载和 Agent 注入；`agent-core` 只提供 skill 注册和使用所需的基础能力，不负责 Skill Hub 接入与治理。

## 2. 能力范围

| 能力 | 需求范围 |
|---|---|
| Skill Hub SPI | Runtime 应提供统一、可替换的 Skill Hub 访问边界。 |
| 连接认证与访问控制 | Runtime 应使用已配置的凭据访问 Skill Hub。凭据缺失、无效或 Skill Hub 拒绝访问时，不得下载或注入 skill。 |
| 安全访问 | Runtime 应安全管理访问凭据，保护认证信息和敏感配置。 |
| skill 下载 | Runtime 应根据 Agent 配置从 Skill Hub 下载所需 skill。 |
| Agent 注入 | Runtime 应将下载成功的 skill 注入 Agent 运行环境，并交由 `agent-core` 注册和使用。 |
| 错误诊断 | 连接认证失败、Skill Hub 拒绝访问、skill 不存在、下载失败或注入失败时，应提供明确且不泄露敏感信息的诊断。 |

## 3. 首版本范围

- 在 Agent 部署或启动阶段获取并注入 skill，不要求运行中动态刷新。
- 定义 Skill Hub SPI，允许后续扩展其他 Skill Hub 实现。
- 提供默认实现，对接 [`openJiuwen/skillhub`](https://gitcode.com/openJiuwen/skillhub) 提供的服务 API。
- 首版本不建立 Agent 与 skill 的独立授权模型；具体 skill 访问权限由 Skill Hub 根据连接凭据判定。
- 支持 Skill Hub 访问凭据的安全配置、加密存储或安全引用，以及使用时解密。

## 4. 安全要求

- 密码、token、认证头和解密后的凭据不得写入日志、错误响应、遥测数据或持久化明文配置。
- 凭据加密和解密过程不得泄露明文、密钥或 token。
- 凭据只应在访问 Skill Hub 所需的最小范围和时间内使用。
- 连接认证失败、Skill Hub 拒绝访问和下载失败不得通过错误信息泄露凭据、内部地址或敏感 skill 内容。
- 下载结果在注入前应确认来自已配置且认证成功的 Skill Hub。

## 5. 边界与不承诺项

- 不定义 Skill Hub 服务端的管理、审批、运营和存储能力。
- 不定义 Agent 与 skill 的独立授权模型，也不在 Runtime 中维护 skill 级授权规则。
- 不定义具体 SPI 接口、配置字段、协议报文、缓存、重试或内部类设计。
- 不定义 `agent-core` 的 skill 格式、解析、执行和模型上下文处理策略。
- 不承诺运行中热更新、请求级动态切换或按用户动态选择 skill。
- 首版本不提供 `openJiuwen/skillhub` 之外的默认 Skill Hub 实现。

## 6. 验收关注

- Runtime 提供可替换的 Skill Hub SPI。
- 默认实现能够通过 `openJiuwen/skillhub` 服务 API 完成 skill 下载。
- Skill Hub 要求认证但未配置凭据时，连接失败且不下载或注入 skill。
- 凭据无效、过期或 Skill Hub 返回 `401/403` 时，访问失败且不下载或注入 skill。
- 只有连接认证成功且 Skill Hub 允许访问时，Runtime 才能继续下载和注入 skill。
- 下载成功的 skill 能够交给 `agent-core` 注册并进入 Agent 运行环境。
- 密码、token 和其他认证信息不会以配置明文形式保存，也不会通过加解密过程、日志、错误响应或遥测泄露。

## 7. 关联文档

- `architecture/L0-Top-Level-Design/boundaries.md`
- `architecture/L0-Top-Level-Design/glossary.md`
- `architecture/L1-High-Level-Design/agent-runtime/README.md`
- `architecture/L1-High-Level-Design/agent-runtime/development.md`
