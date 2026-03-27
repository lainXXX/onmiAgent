# 开发习惯指南 (Development Guidelines)

> 本文档记录项目开发中的编码习惯、注释风格、测试规范等，供 AI 助手遵循。

---

## 1. 注释规范 (Comment Style)

### 1.1 类注释 (Class Javadoc)

```java
/**
 * 类名描述
 *
 * <p>功能概述。</p>
 *
 * <h3>功能说明：</h3>
 * <ul>
 *   <li><b>关键概念</b> - 解释</li>
 *   <li><b>业务规则</b> - 描述</li>
 * </ul>
 *
 * <h3>数据库表结构：</h3>
 * <pre>{@code
 * CREATE TABLE ...
 * }</pre>
 *
 * @author javarem
 * @since 2026-03-26
 * @see RelatedClass
 */
```

**示例** (`TaskProgressRepository.java`):
```java
/**
 * 任务进度追踪Repository
 *
 * <p>负责管理exec_rounds（执行轨计数器）的持久化存储。</p>
 *
 * <h3>功能说明：</h3>
 * <ul>
 *   <li><b>exec_rounds</b> - 自上一次任务状态真正推进以来的对话轮次</li>
 *   <li><b>非完成不归零</b> - 纯文本废话无法骗取归零</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-26
 * @see TaskRepository
 */
```

### 1.2 方法注释 (Method Javadoc)

```java
/**
 * 方法功能描述
 *
 * <p>详细说明（包括边界条件、特殊处理）。</p>
 *
 * @param paramName 参数说明
 * @return 返回值说明
 */
```

### 1.3 行内注释 (Inline Comments)

- **仅在复杂逻辑处添加**，解释"为什么这样做"
- **禁止**解释"代码在做什么"的废话注释
- 使用 **中文** 编写注释

```java
// ✅ 正确：解释原因
// 使用 INSERT ... ON DUPLICATE KEY UPDATE 模式
// 如果记录不存在，先插入（exec_rounds=1），如果存在则+1

// ❌ 错误：解释代码
// 调用 update 方法
jdbcTemplate.update(sql, params);
```

---

## 2. 日志规范 (Logging)

### 2.1 日志格式

```java
log.info("[ClassName] 初始化完成, param={}", value);
log.debug("[ClassName] 方法名: 描述, key={}", key);
log.warn("[ClassName] 方法名: 可恢复错误, error={}", error);
```

### 2.2 日志级别使用

| 级别 | 使用场景 |
|------|----------|
| `info` | 初始化成功、重要业务操作完成 |
| `debug` | 方法入口/出口、流程追踪 |
| `warn` | 可恢复错误、异常情况 |
| `error` | 不可恢复错误、需要人工介入 |

### 2.3 日志示例

```java
log.info("[TaskProgressAdvisor] 初始化完成, ORDER={}, NAG_THRESHOLD={}", ORDER, NAG_THRESHOLD);
log.debug("[TaskProgressRepository] exec_rounds递增, userId={}, sessionId={}, exec_rounds={}", userId, sessionId, newRounds);
log.warn("[TaskUpdate] 无效状态={}, taskId={}", status, taskId);
```

---

## 3. 代码风格 (Code Style)

### 3.1 方法分组

使用分隔线分组相关方法：

```java
// ==================== 构造方法 ====================

// ==================== 核心方法 ====================

// ==================== 工具方法 ====================
```

### 3.2 变量命名

- **语义化**：避免 `data`, `info`, `temp` 等模糊命名
- **驼峰式**：Java 标准命名
- **常量**：全大写下划线分隔 `NAG_THRESHOLD`

```java
// ✅ 正确
String userId;
int activeTaskCount;
private static final int NAG_THRESHOLD = 3;

// ❌ 错误
String n;
int cnt;
private static final int threshold = 3;
```

### 3.3 早期返回 (Early Return)

```java
// ✅ 正确：提前返回，减少嵌套
public void process(User user) {
    if (user == null) {
        log.warn("[Process] user为空");
        return;
    }
    // 主逻辑
}

// ❌ 错误：过度嵌套
public void process(User user) {
    if (user != null) {
        if (user.isActive) {
            // 主逻辑
        }
    }
}
```

### 3.4 代码块分隔

```java
if (activeCount == 0) {
    // 无活跃任务，重置计数
    progressRepository.resetExecRounds(userId, sessionId);
    log.debug("[TaskProgressAdvisor] 无活跃任务, userId={}, sessionId={}", userId, sessionId);
} else {
    // 有活跃任务，计数+1
    int newRounds = progressRepository.incrementExecRounds(userId, sessionId);
    log.debug("[TaskProgressAdvisor] 活跃任务数={}, exec_rounds={}, userId={}, sessionId={}",
        activeCount, newRounds, userId, sessionId);
}
```

---

## 4. 测试规范 (Testing)

### 4.1 测试类结构

```java
package top.javarem.skillDemo.xxx;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类名 + 测试
 *
 * <p>测试功能列表：</p>
 * <ul>
 *   <li>功能点1</li>
 *   <li>功能点2</li>
 * </ul>
 *
 * @author javarem
 * @since 2026-03-26
 */
@SpringBootTest
class XxxTest {

    @Autowired
    private XxxService service;

    // 测试方法...
}
```

### 4.2 测试方法命名

格式：`test方法名_场景_预期结果`

```java
@Test
void testGetExecRounds_initialReturnsZero() { }

@Test
void testIncrementExecRounds_incrementsCorrectly() { }

@Test
void testCountActiveTasks_noTasksReturnsZero() { }
```

### 4.3 测试隔离

使用 `UUID.randomUUID()` 确保测试之间互不影响：

```java
@Test
void testMethod() {
    String uid = "prefix-" + UUID.randomUUID();
    String sid = "session-" + UUID.randomUUID();
    // 测试逻辑...
}
```

### 4.4 断言消息

使用中文描述预期结果：

```java
assertEquals(0, rounds, "新用户的exec_rounds应该为0");
assertTrue(result.contains("创建成功"), "应该返回成功消息");
assertNotNull(task, "查询结果不应为空");
```

### 4.5 测试分组

```java
// ==================== 基础功能测试 ====================

// ==================== 边界条件测试 ====================

// ==================== 集成测试 ====================
```

---

## 5. TDD 开发流程 (TDD Process)

### 5.1 流程

1. **写失败测试** - 先写测试用例，明确预期行为
2. **运行测试** - 验证测试失败
3. **写实现代码** - 最简单的方式通过测试
4. **运行测试** - 验证测试通过
5. **重构代码** - 优化实现，消除冗余

### 5.2 TDD 测试文件位置

| 类型 | 位置 |
|------|------|
| 单元测试 | `src/test/java/top/javarem/skillDemo/...` |
| Repository 测试 | `src/test/java/top/javarem/skillDemo/repository/` |
| Advisor 测试 | `src/test/java/top/javarem/skillDemo/advisor/` |
| 集成测试 | 同目录，命名 `*IntegrationTest.java` |

---

## 6. 代码验证流程 (Verification)

### 6.1 修改后必做

```
1. 编译检查: ./mvnw compile -DskipTests -q
2. 运行测试: ./mvnw test -Dtest=ClassName
3. 验证通过后才算完成
```

### 6.2 编译命令

```bash
# 编译（快速检查）
./mvnw compile -DskipTests -q

# 运行单测
./mvnw test -Dtest=ClassName#methodName

# 运行所有测试
./mvnw test

# 打包
./mvnw clean package -DskipTests
```

---

## 7. Git 提交规范 (Commit)

### 7.1 提交格式

```
<type>(<scope>): <subject>

feat(advisor): add TaskProgressAdvisor for round tracking
fix(repository): correct SQL syntax in countActiveTasks
refactor(tool): simplify task update validation
```

### 7.2 Type 类型

| Type | 含义 |
|------|------|
| `feat` | 新功能 |
| `fix` | 修复 Bug |
| `refactor` | 重构（无功能变动） |
| `style` | 格式化、代码风格 |
| `test` | 测试相关 |
| `docs` | 文档 |

---

## 8. 常见模式 (Common Patterns)

### 8.1 Repository 模式

```java
@Repository
@Slf4j
public class XxxRepository {

    private final JdbcTemplate jdbcTemplate;

    public XxxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Type getXxx(String id) {
        String sql = "SELECT * FROM table WHERE id = ?";
        try {
            return jdbcTemplate.queryForObject(sql, Type.class, id);
        } catch (Exception e) {
            log.warn("[XxxRepository] 查询失败: {}", e.getMessage());
            return null;
        }
    }
}
```

### 8.2 Advisor 模式

```java
@Component
@Slf4j
public class XxxAdvisor implements BaseAdvisor {

    private static final int ORDER = Integer.MAX_VALUE - 100;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 前置处理
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // 后置处理
        return response;
    }
}
```

### 8.3 工具类方法分组

```java
// ==================== 构造方法 ====================

// ==================== 公开方法 ====================

// ==================== 私有方法 ====================

// ==================== 工具方法 ====================
```

---

## 9. 禁区 (Iron Rules)

### 9.1 严禁硬编码

```java
// ❌ 禁止
String apiKey = "sk-xxxxx";

// ✅ 正确
String apiKey = environment.getProperty("api.key");
```

### 9.2 严禁删除测试

- **禁止**删除、跳过现有失败测试
- 测试失败 = 逻辑错误，必须修复代码

### 9.3 禁止私自扩充依赖

引入新依赖必须先征求用户同意。

---

## 10. 项目特定约定

### 10.1 Advisor 执行顺序

| Advisor | Order | 职责 |
|---------|-------|------|
| `ContextCompressionAdvisor` | 4000 | 上下文压缩 |
| `MessageFormatAdvisor` | 10000 | 消息格式化 |
| `LifecycleToolCallAdvisor` | `Integer.MAX_VALUE - 1000` | 工具调用生命周期 |
| `TaskProgressAdvisor` | `Integer.MAX_VALUE - 100` | 任务进度追踪 |

### 10.2 消息格式

日志消息使用 `[ClassName]` 前缀，便于追踪来源。

### 10.3 异常处理

- **可恢复错误**：返回错误结果 + `log.warn`
- **不可恢复错误**：抛出异常 + `log.error`
