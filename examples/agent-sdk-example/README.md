# agent-sdk 示例运行说明

这个目录是 `agent-sdk` 的独立 Java 示例工程，用来验证 SDK 能从 YAML 构造 OpenJiuwen Agent，并真实触发模型、tool、skill 和 rail。

当前示例包含两条 OpenJiuwen 路径：

- `openjiuwen/agent.yaml`：构造并运行 `ReActAgent`。
- `openjiuwen/deepagent.yaml`：构造并运行 `DeepAgent`。

两条路径都会验证：

- 模型：调用 DeepSeek 兼容的 OpenAI Chat Completions 接口。
- tool：调用本地 Java classpath tool。
- skill：加载并使用本地 `skills/` 目录下的 skill。
- rail：触发两个函数式 rail hook，分别是 `afterModelCall` 和 `afterToolCall`。

## 示例结构

```text
examples/agent-sdk-example
  pom.xml
  openjiuwen/
    agent.yaml          # ReActAgent 配置
    deepagent.yaml      # DeepAgent 配置
  skills/
    order-analysis/
      SKILL.md
    report-writing/
      SKILL.md
  src/main/java/com/huawei/ascend/agentsdk/example/
    OpenJiuwenReactAgentSdkExample.java
    OpenJiuwenDeepAgentSdkExample.java
    OpenJiuwenExampleSupport.java
    tools/
      ReadFileTool.java
      QueryOrderTool.java
      CalcDiscountTool.java
    rails/
      ExampleRailHooks.java
  scripts/
    run-openjiuwen.ps1
    run-openjiuwen.sh
```

核心调用链如下：

```text
YAML
  -> AgentFactory.toReactAgent(...) / AgentFactory.toDeepAgent(...)
  -> OpenJiuwen ReActAgent / DeepAgent
  -> DeepSeek 真实模型调用
  -> Java tools + local skills + function rails
  -> Proof Verification
```

## YAML 中的关键配置

两个 YAML 都会注册三个 Java tool：

- `readFile`：绑定 `ReadFileTool.read(...)`，返回证明字段 `readFile-java-tool-executed`。
- `queryOrder`：绑定 `QueryOrderTool.query(...)`，返回证明字段 `queryOrder-java-tool-executed`。
- `calcDiscount`：绑定 `CalcDiscountTool.calculate(...)`，返回证明字段 `calcDiscount-java-tool-executed`。

两个 YAML 都会注册两个 skill：

- `order-analysis`：要求最终回答包含 `ORDER_ANALYSIS_SKILL_USED`。
- `report-writing`：要求最终回答包含 `REPORT_WRITING_SKILL_USED`。

两个 YAML 都会注册两个 rail：

- `example-after-model-call`：事件 `afterModelCall`，绑定 `ExampleRailHooks.afterModelCall(...)`。
- `example-after-tool-call`：事件 `afterToolCall`，绑定 `ExampleRailHooks.afterToolCall(...)`。

rail 的 YAML 形式如下：

```yaml
rails:
  - name: example-after-model-call
    type: function
    event: afterModelCall
    class: com.huawei.ascend.agentsdk.example.rails.ExampleRailHooks
    method: afterModelCall
  - name: example-after-tool-call
    type: function
    event: afterToolCall
    class: com.huawei.ascend.agentsdk.example.rails.ExampleRailHooks
    method: afterToolCall
```

## 前置条件

运行前需要准备：

- JDK 21。
- Maven。
- 可以访问 DeepSeek API 的网络环境。
- Maven 能解析 `com.openjiuwen:agent-core-java:0.1.12-jdk17`。
- 环境变量 `DEEPSEEK_API_KEY` 已设置为真实模型 key。

YAML 中的模型配置如下：

```yaml
model:
  provider: OpenAI
  name: deepseek-chat
  baseUrl: https://api.deepseek.com
  apiKey: ${DEEPSEEK_API_KEY}
  sslVerify: true
```

不要把真实 key 写入 YAML 或代码文件。下面的命令只把 key 放到当前 shell 进程环境变量中。

## Bash 运行步骤

在仓库根目录 `D:\Code\spring-ai-ascend` 或对应的 Bash 路径下执行。

### 1. 设置模型 key

```bash
export DEEPSEEK_API_KEY="你的真实 DeepSeek API Key"
```

建议同时显式设置 UTF-8，避免中文日志乱码：

```bash
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-$LANG}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
```

### 2. 安装本地 agent-sdk

```bash
mvn -f agent-sdk/pom.xml -DskipTests install
```

预期结果：

```text
BUILD SUCCESS
Installing ... agent-sdk-0.2.0-SNAPSHOT.jar ...
```

### 3. 编译示例工程

```bash
mvn -f examples/agent-sdk-example/pom.xml compile
```

预期结果：

```text
BUILD SUCCESS
```

### 4. 运行 ReActAgent 示例

推荐使用脚本：

```bash
bash examples/agent-sdk-example/scripts/run-openjiuwen.sh react
```

也可以直接执行 Maven：

```bash
mvn -f examples/agent-sdk-example/pom.xml compile exec:java \
  "-Dexample.mainClass=com.huawei.ascend.agentsdk.example.OpenJiuwenReactAgentSdkExample"
```

预期结果：命令结束时出现 `verification: PASS`。

### 5. 运行 DeepAgent 示例

推荐使用脚本：

```bash
bash examples/agent-sdk-example/scripts/run-openjiuwen.sh deepagent
```

也可以直接执行 Maven：

```bash
mvn -f examples/agent-sdk-example/pom.xml compile exec:java \
  "-Dexample.mainClass=com.huawei.ascend.agentsdk.example.OpenJiuwenDeepAgentSdkExample"
```

预期结果：命令结束时出现 `verification: PASS`。

## PowerShell 运行步骤

在仓库根目录 `D:\Code\spring-ai-ascend` 下执行。

### 1. 设置模型 key

```powershell
$env:DEEPSEEK_API_KEY = "你的真实 DeepSeek API Key"
```

建议当前 PowerShell 会话先切到 UTF-8，避免 Java 中文 stdout 被 PowerShell 按 GBK/936 解码：

```powershell
chcp 65001
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
$env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS, "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8") -join " ").Trim()
```

说明：`exec:java` 运行在 Maven JVM 内，`file.encoding` / `sun.stdout.encoding` 需要在 JVM 启动前设置。只在 Maven 命令尾部加 `-Dfile.encoding=UTF-8` 通常不能修复这个乱码问题。

### 2. 安装本地 agent-sdk

```powershell
mvn -f agent-sdk\pom.xml -DskipTests install
```

预期结果：

```text
BUILD SUCCESS
Installing ... agent-sdk-0.2.0-SNAPSHOT.jar ...
```

### 3. 编译示例工程

```powershell
mvn -f examples\agent-sdk-example\pom.xml compile
```

预期结果：

```text
BUILD SUCCESS
```

### 4. 运行 ReActAgent 示例

推荐使用脚本。脚本会自动设置当前进程的控制台编码和 JVM UTF-8 参数：

```powershell
.\examples\agent-sdk-example\scripts\run-openjiuwen.ps1 -Agent react
```

也可以直接执行 Maven：

```powershell
mvn -f examples\agent-sdk-example\pom.xml compile exec:java "-Dexample.mainClass=com.huawei.ascend.agentsdk.example.OpenJiuwenReactAgentSdkExample"
```

预期结果：命令结束时出现 `verification: PASS`。

### 5. 运行 DeepAgent 示例

推荐使用脚本：

```powershell
.\examples\agent-sdk-example\scripts\run-openjiuwen.ps1 -Agent deepagent
```

也可以直接执行 Maven：

```powershell
mvn -f examples\agent-sdk-example\pom.xml compile exec:java "-Dexample.mainClass=com.huawei.ascend.agentsdk.example.OpenJiuwenDeepAgentSdkExample"
```

预期结果：命令结束时出现 `verification: PASS`。

## 如何判断 tool 被调用到了

示例不会只依赖模型回答文本，而是在 Java 进程内统计 tool 调用次数。成功输出会包含：

```text
=== Proof Verification ===
queryOrder invocations: 1
calcDiscount invocations: 1
verification: PASS
```

判断依据：

- `queryOrder invocations` 大于 `0`，说明 `QueryOrderTool.query(...)` 被真实调用。
- `calcDiscount invocations` 大于 `0`，说明 `CalcDiscountTool.calculate(...)` 被真实调用。
- 模型最终结果中必须包含 `queryOrder-java-tool-executed`。
- 模型最终结果中必须包含 `calcDiscount-java-tool-executed`。
- 如果上述条件不满足，程序会抛出 `IllegalStateException`，不会打印 `verification: PASS`。

ReActAgent 还会通过自定义 `readFile` tool 读取 skill 文件，通常可以看到：

```text
readFile invocations: 2
```

DeepAgent 默认走 OpenJiuwen 原生 `skill_tool` 读取 skill，因此 DeepAgent 成功时 `readFile invocations` 可以是 `0`，这不代表 skill 没有被使用。

## 如何判断 skill 被调用到了

两个本地 skill 都要求最终回答包含固定证明标记：

```text
ORDER_ANALYSIS_SKILL_USED
REPORT_WRITING_SKILL_USED
```

成功输出会包含：

```text
skill markers: ORDER_ANALYSIS_SKILL_USED, REPORT_WRITING_SKILL_USED
verification: PASS
```

判断依据：

- 最终模型结果包含 `ORDER_ANALYSIS_SKILL_USED`，说明 `order-analysis` skill 的要求进入了模型上下文并被遵循。
- 最终模型结果包含 `REPORT_WRITING_SKILL_USED`，说明 `report-writing` skill 的要求进入了模型上下文并被遵循。
- DeepAgent 日志中还会看到 `skill_tool` 调用，例如 `skill_tool({"skill_name": "order-analysis"})` 和 `skill_tool({"skill_name": "report-writing"})`。

## 如何判断 rail 被触发到了

两个 rail hook 都有进程内计数器。成功输出会包含：

```text
afterModelCall rail invocations: 2
afterToolCall rail invocations: 4
verification: PASS
```

具体数字会随模型调用轮次变化，不要求固定值，但必须大于 `0`。

判断依据：

- `afterModelCall rail invocations` 大于 `0`，说明 `afterModelCall` rail 被模型调用生命周期触发。
- `afterToolCall rail invocations` 大于 `0`，说明 `afterToolCall` rail 被 tool 执行生命周期触发。
- 运行日志中还会打印类似内容：

```text
[agent-sdk-example] rail afterModelCall invoked, count=1, event=after_model_call
[agent-sdk-example] rail afterToolCall invoked, count=1, event=after_tool_call
```

如果任一 rail 计数为 `0`，程序会抛出 `IllegalStateException`，不会打印 `verification: PASS`。

## 成功输出示例

ReActAgent 常见成功尾部：

```text
=== Proof Verification ===
readFile invocations: 2
queryOrder invocations: 1
calcDiscount invocations: 1
afterModelCall rail invocations: 3
afterToolCall rail invocations: 4
skill markers: ORDER_ANALYSIS_SKILL_USED, REPORT_WRITING_SKILL_USED
verification: PASS
```

DeepAgent 常见成功尾部：

```text
=== Proof Verification ===
readFile invocations: 0
queryOrder invocations: 1
calcDiscount invocations: 1
afterModelCall rail invocations: 2
afterToolCall rail invocations: 4
skill markers: ORDER_ANALYSIS_SKILL_USED, REPORT_WRITING_SKILL_USED
verification: PASS
```

`readFile invocations: 0` 对 DeepAgent 是正常现象，因为 DeepAgent 使用 OpenJiuwen 原生 `skill_tool` 路径读取 skill。

## 自定义输入

两个 main class 都支持可选参数：

```text
<yaml-path> <user-input>
```

如果不传参数，示例会使用默认 YAML 和默认中文任务。默认任务会要求模型调用 `queryOrder`、`calcDiscount`，并使用 `order-analysis`、`report-writing` 两个 skill。

PowerShell 示例：

```powershell
mvn -f examples\agent-sdk-example\pom.xml compile exec:java "-Dexample.mainClass=com.huawei.ascend.agentsdk.example.OpenJiuwenReactAgentSdkExample" "-Dexec.args=`"examples\agent-sdk-example\openjiuwen\agent.yaml 查询订单 A-10086 并计算 120 的折扣`""
```

Bash 示例：

```bash
mvn -f examples/agent-sdk-example/pom.xml compile exec:java \
  "-Dexample.mainClass=com.huawei.ascend.agentsdk.example.OpenJiuwenReactAgentSdkExample" \
  '-Dexec.args="examples/agent-sdk-example/openjiuwen/agent.yaml 查询订单 A-10086 并计算 120 的折扣"'
```

## 常见提示

- Maven 输出里如果出现访问 `127.0.0.1:8081` 的 metadata warning，但最后是 `BUILD SUCCESS`，通常不影响本示例运行。
- Windows PowerShell 乱码通常是控制台编码与 Java stdout 编码不一致导致的。优先使用 `scripts/run-openjiuwen.ps1`；如果仍有乱码，以 `verification: PASS`、计数器和 proof marker 为准。
- 示例会真实调用大模型，失败时先确认 `DEEPSEEK_API_KEY`、网络和 `baseUrl` 是否可用。
