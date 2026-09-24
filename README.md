# Node Prerequisites Plugin

## 项目简介

Node Prerequisites Plugin（原 Slave Prerequisites Plugin）允许你在 Job 于某个节点上运行之前，先在该节点上执行一个前置检查脚本。如果脚本返回非零退出码，该节点将被跳过，Jenkins 会尝试将 Job 分配到其他符合条件的节点，或者将 Job 放回构建队列等待合适的节点。

本插件基于 Jenkins 2.277.4 版本开发，支持两层前置条件检查体系：

- **系统级检查**：全局配置的前置检查规则，支持 Groovy 沙盒脚本、Shell 脚本、Windows 批处理命令三种解释器，优先于任务级检查
- **任务级检查**：Job 级别配置的 Shell/Windows/Groovy 脚本，在目标节点上执行

## 功能特性

- **系统级前置检查**：在 `Manage Jenkins > System Configuration` 中配置全局规则，所有 Job 生效
- **优先执行顺序**：系统级检查先执行，通过后再执行任务级检查
- **Groovy 沙盒执行**：系统级检查默认使用 Groovy 沙盒（`SecureASTCustomizer`），限制危险操作
- **多解释器支持**：系统级与任务级检查均支持 Groovy 脚本、Shell 脚本、Windows 批处理命令三种执行方式（系统级 Groovy 走沙盒，Shell/Batch 在节点以进程方式运行）
- **节点选择约束**：系统级规则支持按标签匹配或正则匹配选择目标节点
- **多执行脚本**：每条系统级规则支持创建多个执行脚本，每个脚本可通过模糊匹配（`*`/`?` 通配符）指定目标节点执行
- **节点环境变量**：自动注入节点相关信息作为环境变量，脚本可直接引用
- **异步检查**：通过线程池异步执行所有检查（系统级和任务级），不阻塞 Jenkins 队列调度
- **重试机制**：检查失败后自动重试，可配置重试次数和重试间隔，重试通过后允许队列任务在节点上执行
- **超时控制**：可自定义前置命令超时时间，超时后自动 kill 进程，避免僵尸程序堆积

## 两层检查体系

### 执行顺序

```
Job 进入构建队列
    |
    v
JobPrerequisitesChecker.canTake(node, item)
    |
    +-- Phase 1: 系统级检查（异步，在节点上执行）
    |       |
    |       +-- 遍历所有 SystemPrerequisiteRule
    |       +-- 检查节点是否匹配（all / labels / regex）
    |       +-- 通过 Channel.callAsync() 将 GroovySandboxExecutor 发送到节点
    |       +-- 在节点的 Agent JVM 中执行 Groovy 沙盒脚本
    |       +-- 超过 checkTimeoutSeconds --> 取消 Future，kill 远程执行
    |       +-- 检查失败 --> 记录失败时间，等待重试间隔后重新检查
    |       |       +-- 重试通过 --> 清除重试状态，进入 Phase 2
    |       |       +-- 超过最大重试次数 --> 永久拒绝该节点（BecauseSystemPrerequisitesArentMet）
    |       +-- 全部通过 --> 进入 Phase 2
    |
    +-- Phase 2: 任务级检查（异步，在节点上执行）
            |
            +-- 获取 Job 的 JobPrerequisites 配置
            +-- 通过 Launcher 在目标节点上运行 Shell/Batch/Groovy 脚本
            +-- 注入 NODE_NAME/NODE_IP 等环境变量
            +-- 超过 checkTimeoutSeconds --> proc.kill() 终止进程，避免僵尸
            +-- 检查失败 --> 记录失败时间，等待重试间隔后重新检查
            |       +-- 重试通过 --> 清除重试状态，允许在该节点上构建
            |       +-- 超过最大重试次数 --> 永久拒绝该节点（BecausePrerequisitesArentMet）
            +-- 退出码 == 0 --> 允许在该节点上构建
```

### 重试机制

检查失败后不会立即永久拒绝节点，而是按照系统配置中的重试参数进行自动重试：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `retryCount` | 3 | 最大重试次数（0 = 不重试，立即拒绝） |
| `retryIntervalSeconds` | 30 | 每次重试间隔（秒） |
| `checkTimeoutSeconds` | 60 | 前置命令超时时间（秒），超时后 kill 进程 |

重试流程：
1. 检查失败 → 记录失败时间和重试次数
2. 在重试间隔内 → 节点保持阻塞状态，任务留在队列中
3. 重试间隔到达 → 重新在节点上执行检查
4. 重试通过 → 清除重试状态，允许任务在节点上执行
5. 超过最大重试次数 → 永久拒绝该节点，尝试其他节点

### 超时控制

前置检查脚本在节点上执行时，如果超过配置的超时时间（`checkTimeoutSeconds`），系统会主动终止超时的进程，避免僵尸程序堆积。

**任务级检查（Shell/Batch/Groovy 脚本）**：
1. `Launcher.start()` 启动进程获得 `Proc` 对象
2. 在独立线程中调用 `proc.join()` 等待进程结束
3. `future.get(timeout, TimeUnit.SECONDS)` 带超时等待结果
4. 超时 → 调用 `proc.kill()` 终止进程，取消 Future，返回超时阻塞原因

**系统级检查（按解释器分发）**：
- **Groovy 脚本**：`Channel.callAsync(executor)` 异步发送到节点 Agent JVM，在沙盒中执行；`future.get(timeout)` 带超时等待结果，超时 → `future.cancel(true)` 取消远程执行并返回超时原因
- **Shell / Windows 批处理**：在目标节点上以进程方式运行（与任务级一致），由 `CommandInterpreter`（Shell / BatchFile）创建脚本文件并通过 `Launcher` 启动，超时同样 `proc.kill()` 终止进程

### 系统级 vs 任务级

| 特性 | 系统级检查 | 任务级检查 |
|------|-----------|-----------|
| 配置位置 | Manage Jenkins > System Configuration | Job 配置页面 |
| 执行位置 | 目标节点（通过 Remoting Channel） | 目标节点（通过 Launcher） |
| 执行方式 | Groovy 沙盒 / Shell / Batch（按解释器分发） | Shell/Batch/Groovy（`CommandInterpreter`） |
| 执行模式 | 异步（线程池） | 异步（线程池） |
| 节点选择 | 支持标签匹配、正则匹配 | 所有节点 |
| 优先级 | 高（先执行） | 低（后执行） |
| 适用场景 | 全局节点策略、安全策略 | 单个 Job 的特定前置条件 |

## 环境变量

### 任务级检查（Shell/Batch/Groovy 脚本）

| 变量名 | 说明 | 示例值 |
|--------|------|--------|
| `NODE_NAME` | 节点名称 | `agent-01`、`Built-In` |
| `NODE_HOSTNAME` | 节点主机名（由操作系统报告） | `ubuntu-server-01` |
| `NODE_IP` | 节点 IP 地址 | `192.168.1.100` |
| `NODE_LABELS` | 节点分配的标签（空格分隔） | `linux docker java` |

### 系统级检查

- **Groovy 脚本**：在目标节点的 Agent JVM 中执行，通过 `Channel.call(GroovySandboxExecutor)` 远程调用，使用以下绑定（binding）：

| 变量名 | 类型 | 说明 |
|--------|------|------|
| `NODE_NAME` | `String` | 节点名称 |
| `NODE_HOSTNAME` | `String` | 节点主机名（在节点上解析） |
| `NODE_IP` | `String` | 节点 IP 地址（在节点上解析） |
| `NODE_LABELS` | `String` | 节点标签（空格分隔） |

- **Shell / Windows 批处理命令**：以进程方式在节点运行，同样的信息以环境变量形式注入（`NODE_NAME`、`NODE_HOSTNAME`、`NODE_IP`、`NODE_LABELS`）

## 系统级配置

### 配置入口

`Manage Jenkins > System Configuration > System Prerequisites`

### 节点选择模式

| 模式 | 说明 | 示例 |
|------|------|------|
| `all` | 所有节点 | — |
| `labels` | 匹配任一标签的节点 | `linux docker production` |
| `regex` | 节点名称匹配正则的节点 | `^build-agent-.*$` |

### 多执行脚本

每条系统级规则支持创建多个执行脚本，每个脚本可以独立配置：

- **脚本内容**：Groovy / Shell / Windows Batch 脚本
- **解释器**：每个脚本可选择不同的解释器类型
- **模糊匹配节点模式**：通过通配符指定脚本仅在匹配的节点上执行

#### 模糊匹配语法

| 通配符 | 含义 | 示例 |
|--------|------|------|
| `*` | 匹配任意字符序列 | `build-*` 匹配所有以 `build-` 开头的节点 |
| `?` | 匹配单个字符 | `worker-?` 匹配 `worker-1` 到 `worker-9` |
| `,` | 分隔多个模式（任一匹配即可） | `agent-1,agent-2` 匹配 `agent-1` 或 `agent-2` |

默认值 `*` 表示对所有节点执行。

#### 配置示例

一条规则可以包含多个脚本，分别针对不同节点执行不同检查：

- 脚本 1：`nodePattern = build-*`，Shell 脚本检查磁盘空间
- 脚本 2：`nodePattern = gpu-*`，Shell 脚本检查 GPU 驱动
- 脚本 3：`nodePattern = *`，Groovy 脚本检查节点在线状态

### Groovy 沙盒限制

系统级 Groovy 脚本在 `SecureASTCustomizer` 沙盒中执行，限制如下：

- **导入白名单**：仅允许 `java.io.File`、`java.net.InetAddress`、`java.util.*` 等安全类
- **接收者黑名单**：禁止 `System`、`Runtime`、`Thread`、`ClassLoader`、`ProcessBuilder` 等
- **返回值**：脚本必须返回 `true`（通过）或 `false`（拒绝节点）

### 使用示例

**示例 1：只允许生产环境节点运行特定标签的 Job**

```groovy
// 只允许标签包含 "production" 的节点
def labels = NODE_LABELS.split(" ")
return labels.contains("production")
```

**示例 2：按节点名称正则过滤**

配置节点选择模式为 `regex`，模式为 `^build-.*$`，脚本：

```groovy
// 所有以 build- 开头的节点需要通过检查
return true
```

**示例 3：检查节点是否在线（通过 Jenkins API）**

```groovy
def node = (hudson.model.Node) NODE
def computer = node.toComputer()
if (computer == null) return false
return !computer.isOffline()
```

## 任务级配置

### 在 Job 中启用前置条件检查

1. 打开 Job 的配置页面
2. 找到 **"Check prerequisites before job can build on a node"** 选项
3. 勾选 **"Check job prerequisites"** 复选框
4. 选择解释器类型：
   - **shell script** — 使用 Shell 解释器（Linux/macOS 节点）
   - **windows batch command** — 使用 Windows 批处理（Windows 节点）
   - **groovy script** — 使用 Groovy 解释器（需要节点已安装 Groovy 并配置在 PATH 中）
5. 在脚本输入框中编写前置检查脚本
6. 保存配置

### 使用示例

**Shell 脚本示例：**

```bash
#!/bin/bash
echo "Checking prerequisites on node: $NODE_NAME ($NODE_IP)"

# 检查 Docker 是否可用
if ! command -v docker &> /dev/null; then
    echo "Docker is not installed on $NODE_NAME"
    exit 1
fi

# 检查磁盘空间是否充足
AVAILABLE=$(df -BG / | awk 'NR==2 {print $4}' | tr -d 'G')
if [ "$AVAILABLE" -lt 10 ]; then
    echo "Insufficient disk space on $NODE_NAME: ${AVAILABLE}GB available"
    exit 1
fi

# 根据节点标签选择不同检查逻辑
if echo "$NODE_LABELS" | grep -q "production"; then
    # 生产环境节点额外检查
    test -f /etc/production.lock || exit 1
fi

echo "All prerequisites met for $NODE_NAME"
exit 0
```

**Windows 批处理示例：**

```batch
@echo off
echo Checking prerequisites on node: %NODE_NAME% (%NODE_IP%)

where docker >nul 2>nul
if errorlevel 1 (
    echo Docker is not installed on %NODE_NAME%
    exit /b 1
)

echo All prerequisites met for %NODE_NAME%
exit /b 0
```

**Groovy 脚本示例：**

```groovy
println "Checking prerequisites on node: ${System.getenv('NODE_NAME')} (${System.getenv('NODE_IP')})"

// 检查 Docker 是否可用
def docker = new File('/usr/bin/docker')
if (!docker.exists()) {
    println "Docker is not installed on ${System.getenv('NODE_NAME')}"
    System.exit(1)
}

// 检查磁盘空间是否充足
def process = "df -BG /".execute()
process.waitFor()
def line = process.text.readLines().get(1)
def available = line.split(/\s+/)[3].replaceAll('G', '').toInteger()
if (available < 10) {
    println "Insufficient disk space: ${available}GB available"
    System.exit(1)
}

// 根据节点标签选择不同检查逻辑
def labels = System.getenv('NODE_LABELS') ?: ''
if (labels.contains('production')) {
    def lockFile = new File('/etc/production.lock')
    if (!lockFile.exists()) {
        println "Production lock file not found"
        System.exit(1)
    }
}

println "All prerequisites met for ${System.getenv('NODE_NAME')}"
```

## 构建方式

### 前置要求

- JDK 8+
- Maven 3.5+

### 编译打包

```bash
mvn clean package
```

生成的 HPI 插件包位于 `target/node-prerequisites.hpi`。

### 本地调试

```bash
mvn hpi:run
```

启动后访问 `http://localhost:8080/jenkins/` 即可使用本地 Jenkins 实例调试插件。

### 安装插件

1. 将 `target/node-prerequisites.hpi` 上传到 Jenkins
2. 进入 **Manage Jenkins > Manage Plugins > Advanced**
3. 在 **Upload Plugin** 区域上传 HPI 文件
4. 重启 Jenkins

## CI/CD

项目使用 GitHub Actions 进行持续集成与发布，workflow 配置位于 [.github/workflows/build.yml](.github/workflows/build.yml)。

### 触发条件

| 事件 | 触发条件 | 行为 |
|------|---------|------|
| `push` | 任意分支 | 构建并测试 |
| `push` | `v*` 标签（如 `v1.2`） | 构建、测试、创建 GitHub Release |
| `pull_request` | `main`/`master` 分支 | 构建并测试 |

### 构建流程

```
代码检出 → 设置 JDK 8 → 从 pom.xml 提取版本号 → Maven 编译打包 → 运行测试
    |
    +-- 非 tag push --> 上传 artifact（保留 90 天）
    |
    +-- tag push (v*) --> 上传 artifact + 创建 GitHub Release（自动生成 Release Notes）
```

### 发布新版本

```bash
# 1. 更新 pom.xml 中的版本号（去掉 -SNAPSHOT）
# 2. 提交并打标签
git tag v1.2
git push origin v1.2

# GitHub Actions 将自动构建 HPI 并创建 Release
```

## 版本兼容性

| 插件版本 | Jenkins 版本 | 说明 |
|----------|-------------|------|
| 1.2 | 2.277.4+ | 系统级规则支持多执行脚本，每个脚本支持模糊匹配指定节点执行 |
| 1.2 | 2.277.4+ | 新增节点环境变量注入、Groovy 解释器、系统级前置检查 |
| 1.2 | 2.277.4+ | 系统级规则新增多解释器支持（Groovy 沙盒 / Shell / Windows 批处理），命令框为多行输入 |
| 1.1 | 1.452+ | 原始版本，基础前置检查功能 |

### 依赖

- Jenkins Core: 2.277.4
- Matrix Project Plugin: 1.18+
- FindBugs JSR305: 3.0.2
- Java: 8+

## 技术实现

### 核心类

- **`JobPrerequisites`** — Job 属性类，存储任务级前置检查脚本配置，在目标节点上启动进程执行检查脚本并注入环境变量；超时后通过 `proc.kill()` 终止进程
- **`JobPrerequisitesChecker`** — 队列调度拦截器（`QueueTaskDispatcher`），先执行系统级检查，通过后再执行任务级检查；内置重试机制，跟踪每个检查的失败次数和重试时间
- **`SystemPrerequisitesConfig`** — 全局配置类（`GlobalConfiguration`），存储系统级规则列表、重试次数（`retryCount`）、重试间隔（`retryIntervalSeconds`）、检查超时（`checkTimeoutSeconds`），遍历每条规则的所有脚本，按脚本的 `interpreter` 分发执行：Groovy 走 `Channel.callAsync()` 沙盒，Shell/Batch 在节点以进程方式运行（`CommandInterpreter` + `Launcher`）
- **`SystemPrerequisiteRule`** — 系统级规则数据类，包含多个 `PrerequisiteScript` 脚本条目、节点选择模式（all/labels/regex）、标签、正则等配置；支持向后兼容的单脚本字段
- **`PrerequisiteScript`** — 单个执行脚本数据类，包含脚本内容、解释器（`interpreter`：groovy script / shell script / windows batch command）、模糊匹配节点模式（`nodePattern`，支持 `*` 和 `?` 通配符及逗号分隔多模式）、沙盒开关
- **`GroovySandboxExecutor`** — Groovy 沙盒执行器（`hudson.remoting.Callable`），可序列化，通过 Remoting Channel 发送到节点执行，使用 `SecureASTCustomizer` 限制危险操作
- **`GroovyScript`** — 任务级 Groovy 解释器（`CommandInterpreter`），在节点上通过 `groovy` 命令执行
- **`BecausePrerequisitesArentMet`** — 任务级阻塞原因对象
- **`BecauseSystemPrerequisitesArentMet`** — 系统级阻塞原因对象
- **`NodeInfoCallable`** — 远程调用对象，通过 Jenkins Remoting Channel 在 Agent 上获取真实的主机名和 IP 地址

### 系统级检查流程

1. `JobPrerequisitesChecker.canTake()` 通过线程池异步调用 `SystemPrerequisitesConfig.checkNode(node)`
2. `checkNode(node)` 遍历所有规则
3. 对每条规则，先通过 `appliesToNode(node)` 检查节点是否匹配（规则级过滤：all/labels/regex）
4. 获取规则的有效脚本列表 `getEffectiveScripts()`（优先使用 `scripts` 列表，向后兼容回退到单脚本字段）
5. 遍历每个 `PrerequisiteScript`，通过 `appliesToNode(nodeName)` 检查脚本的模糊匹配模式是否匹配当前节点
6. 按脚本的 `interpreter` 分发：Groovy 构造 `GroovySandboxExecutor`（含脚本和变量）通过 `Channel.call(executor)` 发送到节点沙盒执行；Shell/Batch 在节点以进程方式运行
7. 在节点的 Agent JVM 中执行 Groovy 沙盒脚本（`SecureASTCustomizer` 限制危险操作）
8. 脚本返回 `false` 或抛异常时，返回 `BecauseSystemPrerequisitesArentMet`
9. 所有系统级规则的所有匹配脚本通过后，进入任务级检查

### 重试机制流程

1. `canTake()` 调用 `runWithRetry()` 执行检查
2. 检查通过 → 清除 `RetryState`，返回 `null`（允许节点）
3. 检查失败 → 记录 `RetryState`（`lastFailTime`、`retryCount++`、`lastBlockage`）
4. 后续 `canTake()` 调用时检查 `RetryState`：
   - 重试间隔未到 → 返回 "waiting for retry" 阻塞消息，任务留在队列
   - 重试间隔到达 → 提交新的检查任务到线程池
   - 新检查通过 → 清除 `RetryState`，返回 `null`（允许节点）
   - 新检查失败 → `retryCount++`，重复步骤 4
5. `retryCount > maxRetries` → 返回永久阻塞（`lastBlockage`）

### 任务级检查流程

1. `JobPrerequisitesChecker.canTake()` 获取 Job 的 `JobPrerequisites` 配置
2. 通过线程池异步调用 `JobPrerequisites.check(node)`
3. `check()` 方法调用 `buildNodeEnvironment(node)` 构建环境变量
4. 对于远程 Agent，通过 `Channel.call(new NodeInfoCallable())` 在 Agent 上获取真实主机名和 IP
5. 对于内置节点（无 Channel），在本地直接获取
6. 将 `NODE_NAME`、`NODE_HOSTNAME`、`NODE_IP`、`NODE_LABELS` 作为环境变量传递给 `ProcStarter.envs()`
7. 前置检查脚本启动时即可访问这些环境变量

### Groovy 沙盒机制

`GroovySandboxExecutor` 使用 Groovy 的 `SecureASTCustomizer` 实现沙盒：

- **导入白名单**：`java.lang.Math`、`java.io.File`、`java.net.InetAddress`、`java.util.*`
- **接收者黑名单**：`System`、`Runtime`、`Thread`、`ClassLoader`、`ProcessBuilder`、`Process`、`GroovyShell`、`GroovyClassLoader`
- **编译配置**：通过 `CompilerConfiguration` 添加编译自定义器
- **Binding**：提供 `NODE_NAME`、`NODE_HOSTNAME`、`NODE_IP`、`NODE_LABELS` 等变量（可序列化，通过 Remoting 传输到节点）

## 许可证

LGPL 3.0 — 详见 [LICENSE.txt](LICENSE.txt)

## 原始项目

基于 [jenkinsci/slave-prerequisites-plugin](https://github.com/jenkinsci/slave-prerequisites-plugin) 由 Nicolas De Loof (CloudBees Inc.) 开发。
