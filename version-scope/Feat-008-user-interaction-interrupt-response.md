---
scope: version-draft
module: agent-runtime
feature_type: functional
feature_id: Feat-Func-008
status: draft
updated: 2026-07-13
authority:
  - ../architecture/L0-Top-Level-Design
  - ../architecture/L1-High-Level-Design/agent-runtime
  - ./FEAT-001-standardized-agent-service-entrypoint.md
  - ./FEAT-002-heterogeneous-agent-framework-compatibility.md
  - ./FEAT-005-remote-agent-orchestration.md
  - ./DFX-001-trajectory-observability.md
drives:
  - ../architecture/L1-High-Level-Design/agent-runtime/logical.md
  - ../architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-008-user-interaction-interrupt-response.md
---

# 用户交互中断响应

## 1. 特性定位

FEAT-008 定义 `agent-runtime` 对执行过程中用户交互类型中断进行标准化接收、Task 状态转换、客户端响应和执行恢复的事实要求。Agent、Workflow、Tool 或其他框架执行组件在已有 Task 执行过程中需要用户参与时，可以产生统一的用户交互中断；`agent-runtime` 将当前 Task 推进到 `INPUT_REQUIRED`，通过智能体服务调用响应、流式事件或 Task 查询向客户端返回交互要求，并在标准 A2A 消息入口识别关联到同一 Task 与当前交互轮次的专用结构化响应，转换为框架中立恢复输入后恢复原执行入口。

本特性解决的问题是：不同 Agent 框架、Workflow 引擎和执行组件可能使用不同的中断信号、交互数据和恢复机制，客户端需要通过统一的智能体服务语义感知“当前执行正在等待用户响应”，并将响应准确提交回原执行上下文。平台需要统一用户交互中断的表达方式、Task 状态、交互描述、响应关联、通用校验、多轮交互和恢复结果，使执行组件与客户端不依赖具体框架的私有中断对象或 checkpoint 格式。

本特性所说的用户交互中断是逻辑契约。交互请求至少包含面向用户的提示信息和描述用户响应结构及通用校验规则的 `inputSchema`，并可以按交互类型携带 `options`、`displayMetadata` 和 `businessPayload`，用于表达信息补充、确认、选择、表单填写、授权确认、审批结果回填等需要用户响应后继续执行的交互。不同执行框架可以使用各自的原生中断机制，但进入 `agent-runtime` 后必须转换为统一的用户交互中断结果和交互描述。

对下游设计和实现而言，本特性是当前版本用户交互中断响应能力的事实来源。L2 设计、框架适配、Task 状态转换、智能体服务响应、客户端续接、执行恢复、测试和开发者指南必须以本文定义的外部行为、状态边界和恢复语义为准。当前版本支持同一 Task 多次经历 `WORKING → INPUT_REQUIRED → WORKING`，每轮交互使用独立交互标识关联当前有效请求与用户响应。

本特性面向以下角色：

- Agent、Workflow、Tool 及框架执行组件：在执行过程中产生需要用户参与的中断，并提供统一交互描述；恢复后消费用户响应，执行具体业务判断，并决定继续执行、再次请求用户交互或结束。
- 框架适配器：将不同框架的原生用户交互中断转换为 `agent-runtime` 可消费的统一中断结果，并在恢复时将用户响应和执行上下文适配回原框架。
- `agent-runtime`：接收标准化用户交互中断，将当前 Task 推进到 `INPUT_REQUIRED`，通过智能体服务向客户端暴露交互要求，校验用户响应与 Task、执行上下文和当前交互轮次的关联，并恢复原执行入口。
- 智能体服务客户端：从调用响应、流式事件或 Task 查询中识别用户交互要求，采集用户响应，并通过现有继续消息入口提交到对应 Task 与交互轮次。
- Agent 开发者与平台集成方：为执行组件配置交互描述和业务响应处理逻辑，并将具体框架的中断与恢复能力接入统一 Runtime 契约。
- 测试与验收团队：验证中断产生、`INPUT_REQUIRED` 状态、交互信息返回、响应关联、重复或迟到响应处理、多轮中断以及恢复结果。

本特性聚焦当前存活 Runtime 实例内已有 Task 的用户交互中断与恢复。`agent-runtime` 负责 Runtime Task 状态、交互请求与用户响应的通用结构校验和执行上下文桥接；具体交互内容的业务有效性由发起中断的 Agent、Workflow、Tool 或业务组件判断，具体执行 checkpoint 由对应框架或外部状态能力管理。

## 2. 当前版本能力要求

| 能力 | 要求级别 | 事实要求 |
|---|---|---|
| 统一用户交互中断 | MUST | Agent、Workflow、Tool 或其他框架执行组件必须能够在已有 Task 执行过程中产生统一的用户交互中断。框架适配器必须将原生中断信号转换为 `agent-runtime` 可消费的标准结果。 |
| 结构化交互描述 | MUST | 用户交互中断必须携带面向用户的提示信息和描述用户响应结构及通用校验规则的 `inputSchema`，并可以按交互类型携带 `options`、`displayMetadata` 和 `businessPayload`，以统一表达信息补充、确认、选择、表单填写、授权确认及审批结果回填等交互。 |
| Task 等待状态转换 | MUST | Runtime 接受有效用户交互中断后，必须将产生中断的当前 Task 从 `WORKING` 推进到 `INPUT_REQUIRED`，并保存该轮交互与 Task、执行上下文和框架恢复状态之间的关联。 |
| 单一有效交互请求 | MUST | 同一 Task 同一时刻必须只有一个当前有效的用户交互请求；一次交互请求可以包含多个输入字段。新的交互轮次在前一轮完成或失效后建立。 |
| 智能体服务响应 | MUST | Runtime 必须通过智能体服务调用响应、流式事件和 Task 查询表面一致地暴露 `INPUT_REQUIRED` 状态、当前交互标识及客户端完成交互所需的信息。 |
| 现有续接入口复用 | MUST | 客户端必须能够通过智能体服务现有的 `SendMessage` 入口提交用户响应，并将响应关联到已有 Task/context；Runtime 使用同一 Task 生命周期完成恢复。 |
| 专用续接响应分发 | MUST | Task 处于 `INPUT_REQUIRED` 且存在当前有效交互请求时，Runtime 必须在普通文本输入投影前识别 `DataPart.data.type = USER_INTERACTION_RESPONSE` 的结构化响应，将其转换为 `UserInteractionResponse` 并进入用户交互续接流程。 |
| 普通文本输入兼容 | MUST | 不满足用户交互续接触发条件的普通消息继续遵守 FEAT-001 的文本输入规则；专用续接分发只处理与当前待响应 Task 和交互轮次关联的结构化响应。 |
| 响应关联与幂等 | MUST | 用户响应必须关联当前 Task/context、当前有效交互标识和响应幂等标识。相同响应的重复提交必须得到等价结果；同一交互已经消费后收到不同响应时，必须返回明确冲突结果。 |
| Task 访问校验 | MUST | Runtime 必须依据智能体服务提供的可信 tenant、caller 和 user 上下文校验 Task 查询与用户响应的访问权限，并将实际调用方关联到交互记录和审计事实。 |
| 通用响应校验 | MUST | Runtime 必须校验用户响应的关联信息、当前交互状态、必填字段、输入类型和通用结构。校验未通过时，Task 保持 `INPUT_REQUIRED`，当前交互请求继续有效，并向客户端返回可编程的校验结果。 |
| 原子响应消费 | MUST | 用户响应通过 Task 访问、交互关联、幂等和通用结构校验后，Runtime 必须原子消费当前交互请求，将 Task 从 `INPUT_REQUIRED` 推进到 `WORKING`，并保证同一交互轮次只触发一次执行恢复。 |
| 原执行入口恢复 | MUST | Runtime 必须把有效用户响应、Task/context 关联和恢复所需的 Runtime 执行上下文传递给产生中断的原执行入口。Agent、Workflow、Tool 或业务组件继续执行具体业务判断，并形成后续输出、下一轮交互或终态结果。 |
| 多轮中断与恢复 | MUST | 同一 Task 必须支持顺序发生多轮 `WORKING → INPUT_REQUIRED → WORKING`。每轮交互使用独立标识，迟到响应和当前轮次响应必须能够明确区分。 |
| 等待期间取消 | MUST | 处于 `INPUT_REQUIRED` 的 Task 必须能够通过现有 Task 取消入口执行协作式取消。取消成功后，Task 进入 `CANCELED`，当前交互请求失效，后续响应返回明确的 Task 终态或交互失效结果。 |
| 远端用户交互投影 | MUST | 远端 Agent Task 进入 `INPUT_REQUIRED` 时，本地 Runtime 必须将远端交互要求投影到本地 Task 和智能体服务响应，使客户端使用与本地中断一致的方式完成交互。 |
| 远端 Task 续接 | MUST | 本地 Runtime 收到有效用户响应后，必须依据已建立的本地 Task、远端 Task/context 和 correlation 关联，将响应续接到持有真实等待上下文的远端 Runtime；远端执行结果继续通过既有远程调用链路回灌本地执行入口。 |
| 当前实例内恢复 | MUST | 在拥有 Task、当前交互关联和执行上下文的 Runtime 实例存活期间，用户响应必须能够恢复到正确执行入口，并保持 Task 状态、交互轮次和恢复结果一致。 |
| 明确失败结果 | MUST | Task/context 不存在或不可访问、Task 状态不允许响应、交互标识无效、响应冲突、响应结构非法、恢复执行失败和远端续接失败必须具有可区分的结构化结果。 |
| 可观测与审计 | SHOULD | 用户交互中断、Task 状态变化、交互信息返回、用户响应、访问与校验结果、恢复、再次中断、取消和失败应形成可关联的观察记录，并关联 tenant、caller、user、Task、交互标识、request、trace、结果和耗时；交互内容按照平台策略进行脱敏。 |

## 3. 外部接口与入口要求

FEAT-008 的外部接口由既有 A2A 智能体服务 API 和 Runtime 框架适配 SDK / SPI API 组成。A2A API 继续使用 FEAT-001 定义的 JSON-RPC / SSE 服务表面，本特性扩展其用户交互中断和续接行为；Runtime SDK / SPI API 继续使用 FEAT-002 定义的框架中立执行入口，本特性增加结构化用户交互中断结果和恢复输入。交互请求、响应、失败及其 A2A 投影属于这些 API 的数据契约，在第五章定义。

### 3.1 A2A 智能体服务入口

| API | API 类型 | 调用方 | FEAT-008 扩展的事实要求 |
|---|---|---|---|
| `SendMessage` | A2A JSON-RPC method | 智能体服务客户端 | 执行产生用户交互中断时返回 `INPUT_REQUIRED` Task；客户端续接时，向已有 Task/context 提交一个携带 `DataPart.data.type = USER_INTERACTION_RESPONSE` 结构化响应的消息。Runtime 在普通文本投影前识别该响应，完成访问、交互轮次、幂等和结构校验后恢复原执行入口。 |
| `SendStreamingMessage` | A2A JSON-RPC streaming method | 智能体服务客户端 | 执行产生用户交互中断时返回状态为 `INPUT_REQUIRED` 的标准 Task 状态更新，其中状态消息携带可展示文本和 `DataPart.data.type = USER_INTERACTION_REQUEST` 的结构化交互请求，并按 FEAT-001 的 interrupted stream 语义结束本次发送流。 |
| `GetTask` | A2A JSON-RPC method | 智能体服务客户端 | 返回指定 Task 的当前状态；Task 处于 `INPUT_REQUIRED` 时，当前状态消息必须包含当前有效用户交互请求。 |
| `SubscribeToTask` | A2A JSON-RPC streaming method | 智能体服务客户端 | 按既有 A2A 订阅语义返回 Task 状态事件，使调用方能够观察当前交互请求、响应后的 `WORKING`、后续交互和终态。 |
| `CancelTask` | A2A JSON-RPC method | 智能体服务客户端 | 允许取消处于 `INPUT_REQUIRED` 的 Task；取消成功后 Task 进入 `CANCELED`，当前交互请求失效。 |

### 3.2 Runtime 框架适配 SDK / SPI API

| API | API 类型 | 实现方 / 调用方 | FEAT-008 扩展的事实要求 |
|---|---|---|---|
| `AgentExecutionResult.interrupted(UserInputInterrupt)` | Java SDK factory API | 框架适配器调用 | 根据统一 `UserInputInterrupt` 创建 `INTERRUPTED` 执行结果，供 Runtime 建立交互请求并将 Task 推进到 `INPUT_REQUIRED`。 |
| `StreamAdapter.adapt(Stream<?>) -> Stream<AgentExecutionResult>` | Java SPI function | 框架适配器实现，Runtime 调用 | 将框架原生用户交互信号转换为包含 `UserInputInterrupt` 的标准中断结果，并保持 FEAT-002 定义的其他执行结果语义。 |
| `AgentRuntimeHandler.execute(AgentExecutionContext)` | Java SPI function | 框架适配器实现，Runtime 调用 | Runtime 接受有效用户响应后，以 `inputType = USER_INTERACTION_RESUME` 再次调用产生中断的原执行入口；适配器依据统一恢复上下文继续原 Agent、Workflow 或 Tool 执行。 |
| `AgentExecutionContext.inputType()` | Java SDK context API | 框架适配器读取 | 返回当前执行输入类型；用户交互恢复调用必须返回 `USER_INTERACTION_RESUME`。 |
| `AgentExecutionContext.userInteractionResponse() -> Optional<UserInteractionResponse>` | Java SDK context API | 框架适配器读取 | 在 `USER_INTERACTION_RESUME` 输入下返回已经完成 wire 转换和通用校验的框架中立响应，供适配器恢复原执行框架。 |

## 4. 场景与用户旅程

| 场景 | 前置条件 | 用户/系统动作 | 期望行为 |
|---|---|---|---|
| 本地执行请求用户交互 | Runtime 接入的执行组件正在已有 Task 中执行，并需要用户补充信息、确认、选择或填写表单 | 执行组件通过框架适配器产生携带结构化交互描述的 `UserInputInterrupt` | Runtime 生成 `interactionId`，将当前 Task 推进到 `INPUT_REQUIRED`，通过 `TextPart` 和 `UserInteractionRequest` DataPart 向客户端返回交互要求；客户端通过同一 Task 提交专用结构化响应，Runtime 在普通文本投影前完成识别和转换，Task 进入 `WORKING`，原执行入口以 `USER_INTERACTION_RESUME` 恢复。 |
| 响应填写错误后修正 | Task 处于 `INPUT_REQUIRED`，当前交互请求包含必填字段或输入类型约束 | 客户端提交缺少必填字段或不符合 `inputSchema` 的响应，随后根据校验结果修正并重新提交 | Runtime 首次返回可修正的 `UserInteractionFailure`，保持 Task 和当前交互请求有效；修正后的响应通过校验后只触发一次执行恢复。 |
| 多轮用户交互 | Task 完成一轮有效响应并恢复执行，执行组件仍需要新的用户信息 | 执行组件再次产生 `UserInputInterrupt`，客户端完成新一轮响应 | Runtime 为新一轮生成新的 `interactionId`，Task 再次进入 `INPUT_REQUIRED`；每轮响应只作用于对应交互请求，最终执行结果仍归属于同一 Task。 |
| 用户取消等待中的 Task | Task 处于 `INPUT_REQUIRED`，用户决定停止当前执行 | 客户端调用标准 A2A `CancelTask` | Runtime 将 Task 推进到 `CANCELED`，使当前交互请求失效并记录取消结果；后续交互响应得到一致的 Task 终态或交互失效结果。 |
| 远端 Agent 请求用户交互 | 本地 Task 正在等待远端 Agent 执行，远端 Task 进入 `INPUT_REQUIRED` | 本地 Runtime 将远端交互要求投影为本地 `UserInteractionRequest`；客户端提交响应 | 本地 Runtime 根据内部绑定将有效响应续接到对应远端 Task/context，远端 Runtime 恢复真实 Task；远端后续结果通过既有调用链路回灌本地执行入口。 |

## 5. 行为语义与边界

### 5.1 中断建立与 Task 状态语义

- `AgentRuntimeHandler` 或 `StreamAdapter` 将框架原生用户交互信号转换为 `AgentExecutionResult.INTERRUPTED + UserInputInterrupt`。

`UserInputInterrupt` 是框架适配开发者通过 Runtime SDK 产生的框架中立用户交互中断模型：

| 字段 | 要求 | 事实语义 |
|---|---|---|
| `interactionType` | MUST | 交互类型，当前至少支持 `TEXT_INPUT`、`CONFIRMATION`、`SINGLE_SELECT`、`MULTI_SELECT` 和 `FORM`。 |
| `prompt` | MUST | 面向用户的可展示提示文本。 |
| `inputSchema` | MUST | 描述用户响应结构和通用校验规则的 JSON Schema 兼容结构。 |
| `options` | MAY | 选择类交互的候选值、展示文本和可选说明。 |
| `displayMetadata` | MAY | 客户端呈现交互控件所需的受治理展示信息。 |
| `businessPayload` | MAY | 由发起中断的执行组件解释的业务扩展数据，Runtime 仅按治理策略传递和保护。 |
| `sourceInteractionRef` | MAY | 远端或框架原生交互的受限来源引用，用于内部关联和投影。 |

- Runtime 校验 `interactionType`、`prompt`、`inputSchema`、`options`、`displayMetadata` 和 `businessPayload` 之间的一致性。
- `UserInputInterrupt` 校验失败时，Runtime 返回 `INVALID_INTERACTION_REQUEST`，并将 Task 推进到 `FAILED`；该失败映射到任务层 `INVALID_INPUT`。
- 有效中断由 Runtime 生成当前 Task 内唯一的 `interactionId`，建立 `UserInteractionRequest` 与 Task、执行上下文、框架恢复状态及可选来源交互引用的绑定。
- Runtime 原子建立当前有效交互请求，并将 Task 从 `WORKING` 推进到 `INPUT_REQUIRED`。
- 同一 Task 同一时刻只有一个有效 `UserInteractionRequest`，同一 Task 可以顺序经历多轮 `WORKING → INPUT_REQUIRED → WORKING`。
- 文本输入、确认、选择、表单、授权确认和审批结果回填统一使用 `INPUT_REQUIRED` Task 状态；具体交互方式由 `interactionType`、`inputSchema` 和 `businessPayload` 表达。

### 5.2 交互描述与客户端投影语义

Runtime 接受有效 `UserInputInterrupt` 后生成当前 Task 内唯一的 `interactionId`，并建立客户端可见的 `UserInteractionRequest`。该请求投影到 A2A `DataPart.data`，字段语义如下：

| `UserInteractionRequest` 字段 | 要求 | 事实语义 |
|---|---|---|
| `type` | MUST | 固定为 `USER_INTERACTION_REQUEST`，位于 `DataPart.data.type`，用于客户端识别本特性的结构化交互请求。 |
| `schemaVersion` | MUST | 用户交互载荷 schema 版本，用于客户端兼容性判断。 |
| `interactionId` | MUST | Runtime 生成的当前 Task 内唯一交互轮次标识。 |
| `interactionType` | MUST | 与已校验 `UserInputInterrupt.interactionType` 一致。 |
| `prompt` | MUST | 面向用户的可展示提示文本。 |
| `inputSchema` | MUST | 客户端构造响应和 Runtime 校验 `responseData` 时共同使用的结构约束。 |
| `options` | MAY | 选择类交互的候选值和展示信息。 |
| `displayMetadata` | MAY | 客户端呈现交互控件所需的受治理展示信息。 |
| `businessPayload` | MAY | 在授权和数据治理边界内提供给客户端的业务扩展数据。 |
| `sourceInteractionRef` | MAY | 经过限制的来源交互引用；客户端不依赖它定位 Runtime Task。 |

Task 与 context 关联由承载该请求的 A2A Task、Message 和标准调用参数提供，不接受交互 payload 自行声明 tenant、caller、user 或 Task 所有权。

| `interactionType` | 结构化输入语义 |
|---|---|
| `TEXT_INPUT` | `responseData` 按 `inputSchema` 表达一个或多个文本输入字段。 |
| `CONFIRMATION` | `responseData` 按 schema 表达确认结果及可选补充说明。 |
| `SINGLE_SELECT` | `responseData` 从 schema 声明的允许值中选择一个值。 |
| `MULTI_SELECT` | `responseData` 从 schema 声明的允许值中选择零个或多个值，并遵守数量约束。 |
| `FORM` | `responseData` 按对象 schema 表达多个结构化字段。 |

- 扩展交互类型使用受治理标识，并继续提供 Runtime 支持的 JSON Schema 兼容 `inputSchema`。
- `inputSchema` 定义字段、类型、必填项、枚举值和基础约束；`options` 提供候选值对应的展示文本和说明；`displayMetadata` 用于客户端呈现。

当前版本必须支持以下 JSON Schema 最小子集，并以相同规则校验交互请求与用户响应：

| 关键词 | 当前版本语义 |
|---|---|
| `type` | 支持 `string`、`boolean`、`number`、`integer`、`object` 和 `array`。 |
| `properties` / `required` | 定义对象字段及必填字段集合。 |
| `enum` | 定义确认或选择类输入的允许值集合。 |
| `items` | 定义数组元素结构。 |
| `minLength` / `maxLength` | 定义字符串长度约束。 |
| `minimum` / `maximum` | 定义数值范围约束。 |
| `minItems` / `maxItems` | 定义数组数量约束。 |

包含当前实现无法执行但会影响响应合法性判断的 schema 关键词时，Runtime 必须在建立交互请求前返回 `INVALID_INTERACTION_REQUEST`，避免客户端与服务端使用不同校验规则。
- 授权确认或审批结果回填可以组合 `CONFIRMATION` 或 `FORM` 与 `businessPayload` 表达，具体业务判断由执行组件完成。
- Task 进入 `INPUT_REQUIRED` 时，状态消息使用 `TextPart` 携带可直接展示的 `prompt`，并使用 `DataPart` 携带完整 `UserInteractionRequest`。
- `SendMessage` 返回处于 `INPUT_REQUIRED` 的 Task；`SendStreamingMessage` 返回 interrupted 事件后结束本次发送流。
- `GetTask` 返回当前有效交互请求；`SubscribeToTask` 可以继续观察用户响应后的 `WORKING`、后续交互和终态事件。
- Runtime 按 Task 可见性和调用方访问范围返回交互请求，并按照数据治理策略处理 `prompt`、`options`、`displayMetadata` 和 `businessPayload`。

### 5.3 专用续接分发与普通文本输入语义

客户端通过 `SendMessage` 提交的 `UserInteractionResponse` 投影到 A2A `DataPart.data`，字段语义如下：

| `UserInteractionResponse` 字段 | 要求 | 事实语义 |
|---|---|---|
| `type` | MUST | 固定为 `USER_INTERACTION_RESPONSE`，位于 `DataPart.data.type`，用于 Runtime 在普通文本投影前执行专用续接分发。 |
| `schemaVersion` | MUST | 必须是 Runtime 当前支持的用户交互载荷 schema 版本。 |
| `interactionId` | MUST | 必须指向当前 Task 的当前有效交互请求。 |
| `responseId` | MUST | 客户端生成的响应幂等标识，用于重复提交和冲突判断。 |
| `responseData` | MUST | 用户提交的结构化响应，必须符合当前 `UserInteractionRequest.inputSchema`。 |

响应关联的 Task/context 由 `SendMessage` 的标准 A2A 调用参数和可信服务调用上下文确定。客户端可以同时提供面向用户的 `TextPart`，执行恢复仍以规范化 `responseData` 为事实来源。

- A2A `SendMessage` 到达 Runtime 后，服务入口先识别消息是否携带 `DataPart.data.type = USER_INTERACTION_RESPONSE` 的结构化响应，并结合可信调用上下文和 Task/context 关联决定输入分发方式。
- 消息携带专用响应 DataPart 时，必须进入 FEAT-008 续接分发；形成框架中立响应后，统一按照 5.4 节完成 Task 访问、Task 状态、当前交互请求、幂等和输入结构校验。
- Task 处于 `INPUT_REQUIRED` 且存在当前有效 `UserInteractionRequest` 时，消息必须携带一个专用响应 DataPart；该 Task 的消息统一进入 FEAT-008 续接分发。
- 专用分发首先校验响应类型、schema 版本、DataPart 数量和基本结构，再将 A2A wire 数据转换为框架中立的 `UserInteractionResponse`。
- 消息没有专用响应 DataPart，并且关联 Task 没有当前有效用户交互请求时，按照 FEAT-001 的普通文本输入规则处理；消息中的一般 DataPart 不因此成为 Agent 执行输入。
- 等待用户交互的 Task 收到仅含 `TextPart`、缺少专用响应 DataPart、携带多个专用响应 DataPart 或响应基本结构非法的消息时，Runtime 返回 `INVALID_INTERACTION_RESPONSE`，保持 Task 为 `INPUT_REQUIRED`，也不进入普通文本执行流程。
- 专用响应 DataPart 可以附带用户可读 `TextPart`；该文本用于消息展示和审计投影，执行恢复以规范化 `UserInteractionResponse.responseData` 为事实来源。
- 完成专用分发后，Runtime 继续执行 Task 访问、交互轮次、幂等和 `inputSchema` 校验。全部校验通过后才产生 `USER_INTERACTION_RESUME` 执行输入。

### 5.4 用户响应校验与幂等语义

Runtime 对专用分发形成的 `UserInteractionResponse` 按以下语义处理：

1. 从可信智能体服务调用上下文取得 tenant、caller 和 user；
2. 校验调用方对 Task 的可见性和访问权限；
3. 确认 Task/context 存在且当前状态为 `INPUT_REQUIRED`；
4. 确认 `interactionId` 对应当前有效交互请求；
5. 根据 `responseId` 和响应内容摘要执行幂等与冲突判断；
6. 根据 `inputSchema` 校验 `responseData`；
7. 原子消费当前交互请求并触发一次执行恢复。

幂等规则如下：

- 相同 `responseId` 且响应内容一致时，返回第一次处理的等价结果；
- 相同 `responseId` 携带不同内容时，返回 `RESPONSE_ID_CONFLICT`；
- 当前交互已经被其他响应消费后，再收到新的 `responseId` 时，返回 `INTERACTION_ALREADY_RESPONDED`；
- 上一轮 `interactionId` 的迟到响应不能作用于当前新一轮交互。

Task 访问、交互轮次或 `inputSchema` 校验未通过时，Runtime 不消费当前交互请求，也不调用原执行入口。仍然有效且可修正的交互保持 `INPUT_REQUIRED`，客户端可以根据 `UserInteractionFailure` 修正后重新提交。

### 5.5 执行恢复与多轮交互语义

- 有效响应通过全部校验后，Runtime 原子标记当前交互已响应，并将 Task 从 `INPUT_REQUIRED` 推进到 `WORKING`。
- Runtime 使用 `AgentExecutionContext.inputType = USER_INTERACTION_RESUME` 再次调用产生中断的原执行入口。
- `AgentExecutionContext` 提供规范化的 `UserInteractionResponse`、Runtime 身份范围、Task/context、消息、状态键和可用状态快照。
- Runtime 负责桥接恢复所需的中立上下文；Agent、Workflow 或具体框架负责解释自身 checkpoint 和业务执行位置。
- 执行组件根据 `responseData` 完成业务校验和后续处理。
- 业务校验需要用户修正或补充信息时，执行组件产生新的 `UserInputInterrupt`，Runtime 建立新的 `interactionId` 和交互轮次。
- 原执行入口完成、失败或再次中断时，继续按照 `AgentExecutionResult` 到 Task 状态的统一映射推进。
- Runtime 无法取得恢复上下文时返回 `RESUME_CONTEXT_UNAVAILABLE`；原执行入口恢复过程中发生确定故障时返回 `RESUME_FAILED`，并将 Task 推进到 `FAILED`。

### 5.6 取消与终态语义

- `INPUT_REQUIRED` 是 Task 的非终态，客户端可以通过标准 A2A `CancelTask` 发起协作式取消。
- 取消成功后，Task 进入 `CANCELED`，当前交互请求失效。
- Task 按 Runtime 通用生命周期规则进入任一终态时，当前交互请求同步失效。
- 对已取消或处于其他终态的 Task 提交响应时，Runtime 返回当前 Task 终态和对应交互失效原因。
- 当前版本在拥有 Task、交互绑定和执行上下文的 Runtime 实例存活期间提供中断恢复；外部持久化 Task 状态和 Agent checkpoint 能力可以为更长生命周期恢复提供状态来源。

### 5.7 远端用户交互语义

FEAT-005 定义本地与远端 Task 的绑定、A2A 调用、传输、重试和取消；FEAT-008 在该远程调用链路上定义结构化用户交互请求的本地投影、客户端响应校验以及向远端等待 Task 的续接语义。

- 远端 Runtime 始终推进并拥有远端 Task 状态；本地 Runtime 拥有本地 Task 状态和本地交互请求。
- 远端 Task 返回兼容的结构化交互 DataPart 时，本地 Runtime 校验后将其 `interactionType`、`prompt`、`inputSchema`、`options` 和 `displayMetadata` 投影为本地 `UserInteractionRequest`。
- 远端 Task 只返回 `INPUT_REQUIRED + TextPart` 时，本地 Runtime 将其归一化为 `TEXT_INPUT`，并生成字符串输入 schema。
- 本地 Runtime 为客户端生成本地 `interactionId`，并在内部绑定本地 Task、远端 Task/context、远端交互引用和 correlation。
- 客户端响应通过本地校验后，本地 Task 进入 `WORKING`。远端交互包含兼容结构化交互 DataPart 时，Runtime 根据内部绑定将本地 `responseData`、远端交互引用和稳定的远端幂等关联转换为 `DataPart.data.type = USER_INTERACTION_RESPONSE` 的远端结构化响应，再通过已有远程调用编排和 A2A `SendMessage` 续接；远端交互仅提供 `INPUT_REQUIRED + TextPart` 时，Runtime 将 `TEXT_INPUT` schema 对应的已校验文本值投影为远端兼容的 `TextPart` 后续接对应远端 Task。
- 远端续接使用稳定幂等与 correlation 关联，避免网络重试产生重复远端恢复。
- 远端后续进入 `INPUT_REQUIRED` 时形成新的本地交互轮次；远端完成、失败或取消后，通过既有远程调用链路回灌本地执行入口。
- 远端续接经过受控重试仍失败时，Runtime 返回 `REMOTE_RESUME_FAILED`，并按上游调用失败语义推进本地 Task。

### 5.8 失败与可观测语义

`UserInteractionFailure` 是用户交互中断建立、响应接受和执行恢复失败时使用的统一结构：

| 字段 | 要求 | 事实语义 |
|---|---|---|
| `operation` | MUST | 失败发生的阶段，如中断建立、响应分发、响应校验或执行恢复。 |
| `failureCode` | MUST | 下表定义的可编程失败码。 |
| `description` | MUST | 面向调用方或框架适配开发者的可编程失败说明。 |
| `retryable` | MUST | 表达调用方能否使用相同业务响应和幂等标识重试。 |
| `correctable` | MUST | 表达客户端能否修正响应后继续使用当前交互请求。 |
| `taskId` / `contextId` | SHOULD | 在调用方有权观察时关联对应 A2A Task/context。 |
| `interactionId` | MAY | 已经建立交互轮次时关联对应交互请求。 |
| `requestId` / `traceId` | MUST | 关联失败响应、日志、审计和调用轨迹。 |

当该结构承载于 Task 状态消息的 DataPart 时，`DataPart.data.type` 固定为 `USER_INTERACTION_FAILURE`；承载于 JSON-RPC error 时放入标准 `error.data`。

| `failureCode` | 事实语义 |
|---|---|
| `INVALID_INTERACTION_REQUEST` | 执行组件产生的用户交互中断缺少必填信息、schema 非法或字段组合冲突。 |
| `TASK_NOT_FOUND` | 响应关联的 Task/context 不存在或对当前调用方不可见。 |
| `TASK_STATE_CONFLICT` | Task 当前状态不接受用户交互响应。 |
| `INVALID_INTERACTION_RESPONSE` | 等待交互的 Task 收到缺少专用响应 DataPart、包含多个专用响应 DataPart、响应类型或 schema 版本不受支持、或者基本结构非法的消息。 |
| `INTERACTION_ID_MISMATCH` | 响应指向的交互标识不是当前有效交互请求。 |
| `RESPONSE_SCHEMA_INVALID` | `responseData` 不符合当前 `inputSchema`，客户端可以修正后重新提交。 |
| `RESPONSE_ID_CONFLICT` | 相同响应幂等标识携带了不同响应内容。 |
| `INTERACTION_ALREADY_RESPONDED` | 当前交互已经由一个有效响应消费。 |
| `RESUME_CONTEXT_UNAVAILABLE` | Runtime 无法取得恢复原执行入口所需的执行上下文。 |
| `RESUME_FAILED` | 原执行入口在接受有效响应后的恢复过程中失败。 |
| `REMOTE_RESUME_FAILED` | 用户响应未能通过既有远程调用链路续接到对应远端 Task。 |

| 失败阶段 | A2A 返回表面 | Task 与交互状态 |
|---|---|---|
| `UserInputInterrupt` 非法 | 标准 Task 失败结果，状态消息携带 `UserInteractionFailure` | 当前 Task 进入 `FAILED`，不建立有效交互请求。 |
| 响应基本结构、访问、Task 状态、交互轮次、幂等或 schema 校验失败 | JSON-RPC error，`error.data` 携带 `UserInteractionFailure` | 校验前状态保持不变；仍可修正的交互继续保持 `INPUT_REQUIRED` 和当前有效请求。 |
| 相同 `responseId` 和相同内容的重复提交 | 返回第一次处理的等价结果 | 不重复消费交互请求，不重复恢复执行。 |
| 响应已经接受后缺少恢复上下文或原执行入口恢复失败 | 标准 Task 失败结果，状态消息携带 `UserInteractionFailure` | 当前交互已消费，Task 进入 `FAILED`。 |
| 远端续接经过受控重试后失败 | 按上游调用失败语义返回本地 Task 失败结果 | 本地 Task 进入 `FAILED`；远端 Task 生命周期继续由远端 Runtime 管理。 |

- `UserInteractionFailure.correctable` 表达客户端能否修正当前响应并继续使用同一交互请求。
- `retryable` 表达调用方能否使用同一业务响应和幂等标识重试当前操作。
- 中断建立、Task 状态变化、交互请求返回、用户响应、访问校验、结构校验、恢复、再次中断、取消和远端续接均形成可关联的观察事实。
- 观察信息至少关联 tenant、caller、user、Task/context、`interactionId`、`responseId`、交互类型、schema 版本、request、trace、结果和耗时。
- `prompt`、`responseData`、`options` 和 `businessPayload` 按平台敏感信息策略进行脱敏或摘要记录。

### 5.9 协作职责边界

| 角色 / 模块 | 职责 |
|---|---|
| Agent、Workflow、Tool 或业务执行组件 | 决定何时需要用户参与，提供交互描述，解释用户响应并执行具体业务校验。 |
| `AgentRuntimeHandler` / `StreamAdapter` | 将具体框架的原生中断转换为 `UserInputInterrupt`，并把 Runtime 恢复上下文适配回原执行框架。 |
| `agent-runtime` | 管理交互标识和当前交互请求，推进 Task 状态，投影客户端响应，校验 Task 访问、交互关联、幂等和通用输入结构，并恢复原执行入口。 |
| A2A 智能体服务入口 | 通过标准 Task、Message、SSE、查询、订阅和取消接口承载用户交互请求与响应，并在普通文本输入投影前把符合触发条件的结构化响应交给 FEAT-008 专用续接分发。 |
| 网关与访问控制能力 | 向 Runtime 提供经过认证的 tenant、caller 和 user 上下文。 |
| 智能体服务客户端 | 展示交互要求，采集用户输入，提交结构化响应，并根据错误、Task 状态和后续事件更新交互界面。 |
| Agent 框架或外部状态能力 | 保存和解释具体 Agent、Workflow 或 Tool 的 checkpoint 与业务执行状态。 |
| FEAT-005 本地远程调用编排组件 | 维护本地 Task 与远端 Task 的关联，执行远程 A2A 调用、传输、重试和取消，并回灌远端执行结果。 |
| FEAT-008 远端交互桥接 | 将远端 `INPUT_REQUIRED` 投影为本地结构化交互请求，校验客户端响应，并依据既有 Task 绑定构造远端续接输入。 |
| 远端 Runtime | 拥有并推进远端 Task 状态，消费远端用户响应并继续远端执行。 |

## 6. 对下游设计与实现的约束

- 下游设计必须把本文作为用户交互中断响应能力的方案事实来源，并保持中断建立、交互描述、Task 状态、客户端投影、响应校验、幂等、执行恢复、多轮交互、取消、远端续接和失败语义一致。
- `agent-runtime` 实现必须把 `UserInputInterrupt` 保持为 `AgentExecutionResult.INTERRUPTED` 的框架中立用户交互载荷，并提供统一的 `UserInteractionRequest`、`UserInteractionResponse`、`UserInteractionFailure` 和 `USER_INTERACTION_RESUME` 契约。
- Task 状态实现必须原子完成有效交互请求建立、`WORKING → INPUT_REQUIRED`、响应消费和 `INPUT_REQUIRED → WORKING`，并保证同一 Task 同一时刻只有一个有效交互请求、同一交互轮次只恢复一次。
- A2A 服务适配必须按照 FEAT-001 的标准智能体服务入口实现 `SendMessage`、`SendStreamingMessage`、`GetTask`、`SubscribeToTask` 和 `CancelTask`；对外以 `TextPart` 和 `DataPart.data.type = USER_INTERACTION_REQUEST` 的结构化请求投影 `INPUT_REQUIRED`，对内在普通文本投影前识别 `DataPart.data.type = USER_INTERACTION_RESPONSE` 的结构化响应并交给 FEAT-008 专用续接分发。
- `UserInteractionRequest.inputSchema` 与 `UserInteractionResponse.responseData` 的实现必须使用同一套受支持 JSON Schema 子集完成服务端校验和客户端交互生成；`interactionType`、`options`、`displayMetadata` 和 schema 约束必须保持一致。
- 框架适配器必须将 Agent、Workflow、Tool 或其他执行框架的原生中断转换为 `UserInputInterrupt`，并将 `AgentExecutionContext.USER_INTERACTION_RESUME`、规范化响应和状态桥接回原框架；具体 checkpoint 与业务执行位置继续由对应框架或外部状态能力管理。
- 用户响应实现必须校验固定响应类型、schema 版本和 DataPart 数量，使用可信调用上下文执行 Task 可见性和访问校验，并按照本文定义的 `interactionId`、`responseId`、响应摘要和 schema 规则完成幂等、冲突与可修正失败处理。
- 远端交互集成必须与 FEAT-005 的远程 Agent 编排保持一致：本地 Runtime 维护本地 Task 与远端 Task/context 绑定，投影远端 `INPUT_REQUIRED`，对兼容结构化交互转发专用响应 DataPart，对纯文本交互投影为远端兼容 `TextPart`，并保持本地与远端 Task 各自的生命周期所有权。
- 可观测实现应把用户交互中断、状态变化、请求投影、响应接收、访问校验、结构校验、恢复、再次中断、取消和远端续接关联到 DFX-001 的 trace / span / trajectory 语义，并应用其敏感信息掩码规则。
- 测试必须覆盖普通 TextPart 继续按 FEAT-001 处理、专用响应优先分发、等待交互时缺少或重复响应 DataPart、响应基本结构非法、本地五种标准交互类型、响应 schema 错误及修正、多轮交互、相同响应重试、响应冲突、迟到响应、取消、恢复上下文缺失和恢复执行失败。
- 远端交互测试必须同时覆盖结构化 `UserInteractionRequest` 投影及专用响应 DataPart 转发、纯文本 `INPUT_REQUIRED` 归一化及 TextPart 续接、重复续接防护、远端再次中断、远端完成和远端续接失败。
- 文档和开发者指南必须统一使用 `UserInputInterrupt`、`UserInteractionRequest`、`UserInteractionResponse`、`USER_INTERACTION_RESUME`、`UserInteractionFailure`、`INPUT_REQUIRED`、`interactionId` 和 `responseId` 术语。

## 7. 关联文档

- `architecture/L0-Top-Level-Design/boundaries.md`
- `architecture/L0-Top-Level-Design/constraints.md`
- `architecture/L0-Top-Level-Design/glossary.md`
- `architecture/L1-High-Level-Design/agent-runtime/README.md`
- `architecture/L1-High-Level-Design/agent-runtime/overview.md`
- `architecture/L1-High-Level-Design/agent-runtime/logical.md`
- `architecture/L1-High-Level-Design/agent-runtime/process.md`
- `architecture/L1-High-Level-Design/agent-runtime/scenarios.md`
- `architecture/L1-High-Level-Design/agent-runtime/api-appendix.md`
- `architecture/L1-High-Level-Design/agent-runtime/spi-appendix.md`
- `architecture/L1-High-Level-Design/agent-runtime/physical.md`
- `version-scope/FEAT-001-standardized-agent-service-entrypoint.md`
- `version-scope/FEAT-002-heterogeneous-agent-framework-compatibility.md`
- `version-scope/FEAT-005-remote-agent-orchestration.md`
- `version-scope/DFX-001-trajectory-observability.md`
