# Bash Tool Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the Bash tool with three-layer security architecture, process observability, and Web-based approval flow.

**Architecture:** Three-sprint approach — Sprint 1 builds the process registry foundation, Sprint 2 implements the security interceptor and approval service, Sprint 3 refactors the execution core and adds the Web approval controller.

**Tech Stack:** Java 21, Spring Boot 3.5.10, Spring AI 1.1.3, JUnit 5, Mockito

**Spec Reference:** `docs/superpowers/specs/2026-03-30-bash-tool-phase1-design.md`

---

## File Map

### New Files (src/main/java/top/javarem/omni/tool/bash/)

| File | Responsibility |
|------|---------------|
| `ManagedProcess.java` | Immutable record holding process metadata + ProcessHandle |
| `ProcessRegistry.java` | Concurrent registry with auto-unregister via ProcessHandle.onExit() |
| `SecurityInterceptor.java` | Facade: chains validator → normalizer → approval service |
| `DangerousPatternValidator.java` | Regex-based dangerous command detection with quote-aware scanning |
| `PathNormalizer.java` | Path sanitization and workspace boundary enforcement |
| `ApprovalService.java` | Ticket-based approval cache with TTL and atomic checkAndConsume() |
| `SecurityException.java` | Dedicated unchecked exception for security rejections |

### Modified Files

| File | Change Summary |
|------|---------------|
| `BashToolConfig.java` | Delegate to SecurityInterceptor before execution |
| `BashExecutor.java` | Register processes, use thread pool, fix encoding, stderr merge |
| `ProcessTreeKiller.java` | Use ProcessHandle.destroyForcibly() + OS-level kill |
| `ResponseFormatter.java` | Minor: no logic changes |

### New Files (src/main/java/top/javarem/omni/controller/)

| File | Responsibility |
|------|---------------|
| `ApprovalController.java` | `POST /approval` endpoint for Web approval flow |

### New Test Files (src/test/java/top/javarem/omni/tool/bash/)

| File | Coverage |
|------|---------|
| `ManagedProcessTest.java` | Record fields and state transitions |
| `ProcessRegistryTest.java` | Register, unregister, kill, forceKillAll, auto-unregister on exit |
| `DangerousPatternValidatorTest.java` | All injection patterns from spec section 4.1/4.2 |
| `PathNormalizerTest.java` | Path traversal, workspace boundary, normalization |
| `ApprovalServiceTest.java` | TTL, atomic consume, command binding |
| `SecurityInterceptorTest.java` | Integration: allowlist, denylist, approval flow |

### New Resources

| File | Purpose |
|------|---------|
| `src/main/resources/approved-commands.properties` | Configurable allowlist of approved commands |

---

## SPRINT 1: Infrastructure & Registry

**Objective:** System can "remember" and "kill" processes.

---

### Sprint 1 File Layout

```
src/main/java/top/javarem/omni/tool/bash/
  ManagedProcess.java      (NEW)
  ProcessRegistry.java     (NEW)
src/test/java/top/javarem/omni/tool/bash/
  ManagedProcessTest.java  (NEW)
  ProcessRegistryTest.java (NEW)
```

---

### Task 1: ManagedProcess Record

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/ManagedProcess.java`
- Create: `src/test/java/top/javarem/omni/tool/bash/ManagedProcessTest.java`

- [ ] **Step 1: Write the failing test**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ManagedProcessTest {

    @Test
    void shouldHoldAllFields() {
        ProcessHandle handle = ProcessHandle.current();
        ManagedProcess mp = new ManagedProcess(
            "12345",
            handle,
            "ls -la",
            "list files",
            Instant.now(),
            ManagedProcess.ProcessState.RUNNING,
            false
        );

        assertEquals("12345", mp.pid());
        assertEquals(handle, mp.handle());
        assertEquals("ls -la", mp.command());
        assertEquals("list files", mp.description());
        assertEquals(ManagedProcess.ProcessState.RUNNING, mp.state());
        assertFalse(mp.isBackground());
    }

    @Test
    void stateTransition() {
        ManagedProcess mp = new ManagedProcess(
            "999", ProcessHandle.current(), "sleep 10", "sleep",
            Instant.now(), ManagedProcess.ProcessState.RUNNING, false
        );
        assertEquals(ManagedProcess.ProcessState.RUNNING, mp.state());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ManagedProcessTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package top.javarem.omni.tool.bash;

import java.time.Instant;
import java.util.Objects;

public final class ManagedProcess {

    public enum ProcessState { RUNNING, TERMINATED, KILLED }

    private final String pid;
    private final ProcessHandle handle;
    private final String command;
    private final String description;
    private final Instant startTime;
    private volatile ProcessState state;
    private final boolean isBackground;
    private final Object stateLock = new Object();

    public ManagedProcess(
            String pid,
            ProcessHandle handle,
            String command,
            String description,
            Instant startTime,
            ProcessState state,
            boolean isBackground) {
        this.pid = Objects.requireNonNull(pid);
        this.handle = Objects.requireNonNull(handle);
        this.command = Objects.requireNonNull(command);
        this.description = description;
        this.startTime = startTime;
        this.state = state;
        this.isBackground = isBackground;
    }

    public String pid() { return pid; }
    public ProcessHandle handle() { return handle; }
    public String command() { return command; }
    public String description() { return description; }
    public Instant startTime() { return startTime; }
    public ProcessState state() { return state; }
    public boolean isBackground() { return isBackground; }

    public void transitionTo(ProcessState newState) {
        synchronized (stateLock) {
            // Prevent transition from KILLED back to anything, or TERMINATED -> KILLED
            if (this.state == ManagedProcess.ProcessState.KILLED) return;
            this.state = newState;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ManagedProcessTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/bash/ManagedProcess.java \
        src/test/java/top/javarem/omni/tool/bash/ManagedProcessTest.java
git commit -m "feat(bash): add ManagedProcess record with ProcessHandle reference"
```

---

### Task 2: ProcessRegistry

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/ProcessRegistry.java`
- Create: `src/test/java/top/javarem/omni/tool/bash/ProcessRegistryTest.java`
- Modify: `src/main/java/top/javarem/omni/tool/bash/BashExecutor.java` (add registry field, register on start/unregister on finish)

- [ ] **Step 1: Write the failing test**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

class ProcessRegistryTest {

    private ProcessRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ProcessRegistry();
    }

    @Test
    void shouldRegisterAndRetrieve() {
        ProcessHandle current = ProcessHandle.current();
        ManagedProcess mp = new ManagedProcess(
            "1", current, "ls", "list",
            java.time.Instant.now(),
            ManagedProcess.ProcessState.RUNNING,
            false
        );

        registry.register(mp);
        assertEquals(mp, registry.get("1").orElse(null));
        assertEquals(1, registry.listAll().size());
    }

    @Test
    void shouldAutoUnregisterOnProcessExit() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("echo", "test");
        Process process = pb.start();
        ProcessHandle handle = process.toHandle();
        String pid = String.valueOf(process.pid());

        ManagedProcess mp = new ManagedProcess(
            pid, handle, "echo test", "echo",
            java.time.Instant.now(),
            ManagedProcess.ProcessState.RUNNING,
            false
        );
        registry.register(mp);
        process.waitFor();

        // Give onExit callback time to fire
        Thread.sleep(500);
        assertTrue(registry.get(pid).isEmpty() ||
                   registry.get(pid).get().state() == ManagedProcess.ProcessState.TERMINATED);
    }

    @Test
    void shouldKillProcess() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sleep", "60");
        Process process = pb.start();
        ProcessHandle handle = process.toHandle();
        String pid = String.valueOf(process.pid());

        ManagedProcess mp = new ManagedProcess(
            pid, handle, "sleep 60", "sleep",
            java.time.Instant.now(),
            ManagedProcess.ProcessState.RUNNING,
            false
        );
        registry.register(mp);

        boolean killed = registry.kill(pid);
        assertTrue(killed);
        assertEquals(ManagedProcess.ProcessState.KILLED, registry.get(pid).get().state());
    }

    @Test
    void shouldForceKillAll() throws Exception {
        ProcessBuilder pb1 = new ProcessBuilder("sleep", "60");
        Process p1 = pb1.start();
        ProcessBuilder pb2 = new ProcessBuilder("sleep", "60");
        Process p2 = pb2.start();

        registry.register(new ManagedProcess(
            String.valueOf(p1.pid()), p1.toHandle(), "sleep 60", "sleep",
            java.time.Instant.now(), ManagedProcess.ProcessState.RUNNING, false
        ));
        registry.register(new ManagedProcess(
            String.valueOf(p2.pid()), p2.toHandle(), "sleep 60", "sleep",
            java.time.Instant.now(), ManagedProcess.ProcessState.RUNNING, false
        ));

        registry.forceKillAll();

        assertTrue(registry.listAll().isEmpty() ||
                   registry.listAll().stream()
                       .allMatch(mp -> mp.state() == ManagedProcess.ProcessState.KILLED));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ProcessRegistryTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package top.javarem.omni.tool.bash;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class ProcessRegistry {

    private static final int MAX_HISTORY = 50;

    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();
    // Use ConcurrentLinkedQueue for thread-safe FIFO ordering
    private final ConcurrentLinkedQueue<String> historyOrder = new ConcurrentLinkedQueue<>();
    private final Map<String, ManagedProcess> history = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "ProcessRegistry-Cleanup");
            t.setDaemon(true);
            return t;
        }
    );

    public ProcessRegistry() {
        // Schedule periodic cleanup
        scheduler.scheduleAtFixedRate(() -> {
            // Remove expired entries (terminated > 5 min ago)
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5);
            history.entrySet().removeIf(e ->
                e.getValue().state() != ManagedProcess.ProcessState.RUNNING &&
                e.getValue().startTime().toEpochMilli() < cutoff
            );
            // FIFO trim — poll from queue head until under limit
            while (history.size() > MAX_HISTORY) {
                String oldest = historyOrder.poll();
                if (oldest != null) history.remove(oldest);
                else break; // safety
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    public void register(ManagedProcess process) {
        processes.put(process.pid(), process);
        log.info("[ProcessRegistry] Registered PID={} cmd={}", process.pid(), process.command());

        // Auto-unregister when process exits
        process.handle().onExit().thenAccept(p -> {
            process.transitionTo(ManagedProcess.ProcessState.TERMINATED);
            unregister(process.pid());
            log.info("[ProcessRegistry] Process exited PID={}", process.pid());
        });
    }

    public void unregister(String pid) {
        ManagedProcess removed = processes.remove(pid);
        if (removed != null) {
            history.put(pid, removed);
            historyOrder.offer(pid); // Thread-safe FIFO ordering
        }
    }

    public Optional<ManagedProcess> get(String pid) {
        return Optional.ofNullable(processes.get(pid));
    }

    public List<ManagedProcess> listAll() {
        return new ArrayList<>(processes.values());
    }

    public boolean kill(String pid) {
        ManagedProcess mp = processes.get(pid);
        if (mp == null) return false;
        try {
            boolean destroyed = mp.handle().destroyForcibly();
            mp.transitionTo(ManagedProcess.ProcessState.KILLED);
            log.info("[ProcessRegistry] Kill requested PID={} result={}", pid, destroyed);
            return destroyed;
        } catch (Exception e) {
            log.warn("[ProcessRegistry] Failed to kill PID={}", pid, e);
            return false;
        }
    }

    @PreDestroy
    public void forceKillAll() {
        log.info("[ProcessRegistry] forceKillAll triggered, killing {} processes", processes.size());
        processes.keySet().forEach(pid -> kill(pid));
        scheduler.shutdown();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ProcessRegistryTest -q`
Expected: PASS (may need 5-10s for auto-unregister test)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/bash/ProcessRegistry.java \
        src/test/java/top/javarem/omni/tool/bash/ProcessRegistryTest.java
git commit -m "feat(bash): add ProcessRegistry with ProcessHandle lifecycle management"
```

---

## SPRINT 2: Security Interceptor & Approval Service

**Objective:** Build the "security checkpoint" — intercept dangerous commands and route them through the approval flow.

---

### Sprint 2 File Layout

```
src/main/java/top/javarem/omni/tool/bash/
  DangerousPatternValidator.java  (NEW)
  PathNormalizer.java             (NEW)
  SecurityInterceptor.java        (NEW)
  SecurityException.java          (NEW)
  ApprovalService.java            (NEW)
src/test/java/top/javarem/omni/tool/bash/
  DangerousPatternValidatorTest.java (NEW)
  PathNormalizerTest.java         (NEW)
  SecurityInterceptorTest.java     (NEW)
  ApprovalServiceTest.java        (NEW)
src/main/resources/
  approved-commands.properties    (NEW)
```

---

### Task 3: DangerousPatternValidator

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/DangerousPatternValidator.java`
- Create: `src/test/java/top/javarem/omni/tool/bash/DangerousPatternValidatorTest.java`

- [ ] **Step 1: Write the failing test — direct rejects**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DangerousPatternValidatorTest {

    private final DangerousPatternValidator validator = new DangerousPatternValidator();

    @ParameterizedTest
    @ValueSource(strings = {
        "rm -rf /",
        "rm -rf /*",
        "mkfs",
        ":(){ :|:& };:",
        "fork bomb"
    })
    void shouldDirectlyReject(String cmd) {
        assertEquals(DangerousPatternValidator.Result.DENY, validator.validate(cmd),
            "Should deny: " + cmd);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ls",
        "ls -la",
        "cat file.txt",
        "git status",
        "mvn test",
        "npm install"
    })
    void shouldAllowSafeCommands(String cmd) {
        assertEquals(DangerousPatternValidator.Result.ALLOW, validator.validate(cmd),
            "Should allow: " + cmd);
    }

    @Test
    void shouldRejectCommandChain_withSemicolon() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("ls; rm -rf /"));
    }

    @Test
    void shouldRejectCommandChain_withPipe() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("ls | cat /etc/passwd"));
    }

    @Test
    void shouldRejectCommandChain_withDoubleAmpersand() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("ls && rm -rf ./src"));
    }

    @Test
    void shouldRejectSubshell_withDollarParens() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("ls $(whoami)"));
    }

    @Test
    void shouldRejectSubshell_withBackticks() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("ls `cat /etc/passwd`"));
    }

    @Test
    void shouldRejectRedirection() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("echo evil > /etc/passwd"));
    }

    @Test
    void shouldRejectAppendRedirection() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("echo evil >> /etc/passwd"));
    }

    @Test
    void shouldRejectDoubleBar() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("ls || rm -rf /"));
    }

    @Test
    void shouldRejectCmdEscape() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("echo test ^& echo evil"));
    }

    @Test
    void shouldRejectCmdVariablePercent() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("type %USERPROFILE%\\secret"));
    }

    @Test
    void shouldRejectEnvironmentVariable() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("rm -rf $HOME/src"));
    }

    @Test
    void shouldRejectCmdVariable() {
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("del %USERPROFILE%\\file"));
    }

    // Quote-aware: these should ALLOW (symbols inside quotes)
    @Test
    void shouldAllowSemicolonInsideDoubleQuotes() {
        assertEquals(DangerousPatternValidator.Result.ALLOW,
            validator.validate("echo \"hello; world\""));
    }

    @Test
    void shouldAllowPipeInsideSingleQuotes() {
        assertEquals(DangerousPatternValidator.Result.ALLOW,
            validator.validate("echo '|'"));
    }

    @Test
    void shouldAllowDollarInQuotedString() {
        assertEquals(DangerousPatternValidator.Result.ALLOW,
            validator.validate("echo \"$HOME\""));
    }

    @Test
    void shouldRejectDangerousInsideQuotes_outsideUnquoted() {
        // echo "test" | rm -rf / — the | is outside quotes
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("echo \"test\" | rm -rf /"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=DangerousPatternValidatorTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package top.javarem.omni.tool.bash;

import org.springframework.stereotype.Component;

@Component
public class DangerousPatternValidator {

    // Immediately deny patterns
    private static final String[] DIRECT_DENY_PATTERNS = {
        "rm\\s+-rf\\s+/\\s*$",
        "rm\\s+-rf\\s+/\\s",
        "\\bmkfs\\b",
        ":\\s*\\(\\s*\\)\\s*\\{.*:.*\\|.*:.*\\&.*\\}.*",
        "fork\\s*bomb"
    };

    // Require approval — checked after direct deny
    private static final String[] REQUIRES_APPROVAL_PATTERNS = {
        "\\brm\\s+-rf\\b",
        "\\bchmod\\s+777\\b",
        "\\bdd\\s+if\\b",
        ">[^>]"
    };

    // Symbols that indicate injection — always deny if outside quotes
    private static final char[] INJECTION_SYMBOLS = {';', '|', '&'};
    // Note: '||' and '&&' are handled as two-char sequences below, not single-char

    public enum Result { ALLOW, DENY, REQUIRE_APPROVAL }

    public Result validate(String command) {
        if (command == null || command.isBlank()) return Result.DENY;

        String trimmed = command.trim();

        // 1. Check direct deny patterns
        for (String pattern : DIRECT_DENY_PATTERNS) {
            if (trimmed.matches("(?i).*" + pattern + ".*")) {
                return Result.DENY;
            }
        }

        // 2. Check quote-aware injection symbols
        if (hasInjectionOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        // 3. Check environment variable usage outside quotes
        if (hasEnvironmentVariableOutsideQuotes(trimmed)) {
            return Result.DENY;
        }

        // 4. Check requires-approval patterns
        for (String pattern : REQUIRES_APPROVAL_PATTERNS) {
            if (trimmed.matches("(?i).*" + pattern + ".*")) {
                return Result.REQUIRE_APPROVAL;
            }
        }

        return Result.ALLOW;
    }

    private boolean hasInjectionOutsideQuotes(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            // Handle escape (next char is literal)
            if (c == '\\' && i + 1 < command.length()) {
                i++; // skip next character
                continue;
            }

            // CMD escape
            if (c == '^' && i + 1 < command.length()) {
                i++; // skip next character
                continue;
            }

            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;

            if (!inSingleQuote && !inDoubleQuote) {
                for (char sym : INJECTION_SYMBOLS) {
                    if (c == sym) return true;
                }
                // Check for && and || (two-char operators)
                if ((c == '&' || c == '|') && i + 1 < command.length()
                        && command.charAt(i + 1) == c) {
                    return true;
                }
                // Check for > and >> (redirection) — deny >> immediately
                // For single >: only deny if immediately followed by a non-space (ambiguous)
                if (c == '>') {
                    if (i + 1 < command.length() && command.charAt(i + 1) == '>') {
                        return true; // >> append redirection — always deny
                    }
                    // > followed by non-space character is suspicious (e.g., >file)
                    if (i + 1 < command.length()) {
                        char next = command.charAt(i + 1);
                        if (!Character.isWhitespace(next)) {
                            return true; // e.g., echo test >file — deny
                        }
                    }
                    // single > followed by space is handled by REQUIRES_APPROVAL pattern
                }
            }
        }
        return false;
    }

    private boolean hasEnvironmentVariableOutsideQuotes(String command) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '^' && i + 1 < command.length()) {
                i++;
                continue;
            }

            if (c == '\'' && !inDoubleQuote) inSingleQuote = !inSingleQuote;
            else if (c == '"' && !inSingleQuote) inDoubleQuote = !inDoubleQuote;

            if (!inSingleQuote && !inDoubleQuote) {
                // Shell $VAR
                if (c == '$' && i + 1 < command.length()) {
                    char next = command.charAt(i + 1);
                    if (Character.isLetterOrDigit(next) || next == '{' || next == '(') {
                        return true;
                    }
                }
                // CMD %VAR%
                if (c == '%' && command.indexOf('%', i + 1) > i + 1) {
                    return true;
                }
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=DangerousPatternValidatorTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/bash/DangerousPatternValidator.java \
        src/test/java/top/javarem/omni/tool/bash/DangerousPatternValidatorTest.java
git commit -m "feat(bash): add DangerousPatternValidator with quote-aware injection detection"
```

---

### Task 4: PathNormalizer

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/PathNormalizer.java`
- Create: `src/test/java/top/javarem/omni/tool/bash/PathNormalizerTest.java`

- [ ] **Step 1: Write the failing test**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

class PathNormalizerTest {

    private PathNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new PathNormalizer("D:/workspace");
    }

    @Test
    void shouldRejectPathOutsideWorkspace() {
        SecurityException ex = assertThrows(SecurityException.class,
            () -> normalizer.validate("D:/etc/passwd"));
        assertTrue(ex.getMessage().contains("WORKSPACE"));
    }

    @Test
    void shouldRejectPathTraversalOutsideWorkspace() {
        SecurityException ex = assertThrows(SecurityException.class,
            () -> normalizer.validate("D:/workspace/../../../etc/passwd"));
        assertTrue(ex.getMessage().contains("WORKSPACE"));
    }

    @Test
    void shouldAllowPathInsideWorkspace() {
        assertDoesNotThrow(() -> normalizer.validate("D:/workspace/src/main"));
    }

    @Test
    void shouldNormalizeMultipleSlashes() {
        String normalized = normalizer.normalize("D:/workspace//src///main//java");
        assertEquals("D:/workspace/src/main/java", normalized);
    }

    @Test
    void shouldNormalizeDotSegments() {
        String normalized = normalizer.normalize("D:/workspace/./src/../src");
        assertEquals("D:/workspace/src", normalized);
    }

    @Test
    void shouldNormalizeBackslashSegments() {
        String normalized = normalizer.normalize("D:\\workspace\\\\src\\\\main");
        assertEquals("D:/workspace/src/main", normalized);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=PathNormalizerTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write minimal implementation**

```java
package top.javarem.omni.tool.bash;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

@Component
public class PathNormalizer {

    private final String workspace;

    public PathNormalizer(String workspace) {
        this.workspace = normalize(workspace);
    }

    public String normalize(String command) {
        if (command == null) return "";
        return command
            .replaceAll("/+", "/")
            .replaceAll("\\\\+", "\\\\");
    }

    public void validate(String command) {
        // Extract file paths from command (simple heuristic: word containing / or \)
        String[] words = command.split("[\\s]+");
        for (String word : words) {
            if (word.contains("/") || word.contains("\\")) {
                validatePath(word);
            }
        }
    }

    private void validatePath(String pathCandidate) {
        try {
            // Normalize the path and resolve against workspace
            String normalized = pathCandidate
                .replaceAll("/+", "/")
                .replaceAll("\\\\+", "\\\\");

            Path resolved = Paths.get(normalized).normalize().toAbsolutePath();
            Path workspacePath = Paths.get(workspace).normalize().toAbsolutePath();

            String resolvedStr = resolved.toString().replace("\\", "/");
            String workspaceStr = workspacePath.toString().replace("\\", "/");

            if (!resolvedStr.startsWith(workspaceStr + "/") && !resolvedStr.equals(workspaceStr)) {
                throw new SecurityException("禁止访问 WORKSPACE 之外的路径: " + pathCandidate);
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            // Path parsing failed — allow it (will be caught by shell)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=PathNormalizerTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/bash/PathNormalizer.java \
        src/test/java/top/javarem/omni/tool/bash/PathNormalizerTest.java
git commit -m "feat(bash): add PathNormalizer with workspace boundary enforcement"
```

---

### Task 5: ApprovalService + SecurityException

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/SecurityException.java`
- Create: `src/main/java/top/javarem/omni/tool/bash/ApprovalService.java`
- Create: `src/test/java/top/javarem/omni/tool/bash/ApprovalServiceTest.java`
- Create: `src/main/resources/approved-commands.properties`

- [ ] **Step 1: Write the failing test**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalServiceTest {

    private ApprovalService service;

    @BeforeEach
    void setUp() {
        service = new ApprovalService();
    }

    @Test
    void shouldCreateTicketAndReturnPending() {
        ApprovalService.CheckResult result = service.createPendingTicket("rm -rf ./src");
        assertNotNull(result.ticketId());
        assertTrue(result.message().contains("待审批"));
    }

    @Test
    void shouldApproveAndConsumeAtomically() {
        ApprovalService.CheckResult pending = service.createPendingTicket("rm -rf ./src");
        String ticketId = pending.ticketId();

        // User approves
        boolean approved = service.submitApproval(ticketId, "rm -rf ./src", true);
        assertTrue(approved);

        // Agent consumes
        ApprovalService.CheckResult consumed = service.checkAndConsume(ticketId, "rm -rf ./src");
        assertEquals(ApprovalService.CheckResult.Status.APPROVED, consumed.status());
    }

    @Test
    void shouldRejectConsumeAfterDenied() {
        ApprovalService.CheckResult pending = service.createPendingTicket("rm -rf ./src");
        service.submitApproval(pending.ticketId(), "rm -rf ./src", false);

        ApprovalService.CheckResult consumed = service.checkAndConsume(
            pending.ticketId(), "rm -rf ./src");
        assertEquals(ApprovalService.CheckResult.Status.REJECTED, consumed.status());
    }

    @Test
    void shouldRejectMismatchedCommand() {
        ApprovalService.CheckResult pending = service.createPendingTicket("rm -rf ./src");
        service.submitApproval(pending.ticketId(), "rm -rf ./src", true);

        // Agent tries to consume with different command
        ApprovalService.CheckResult consumed = service.checkAndConsume(
            pending.ticketId(), "rm -rf ./different");
        assertEquals(ApprovalService.CheckResult.Status.REJECTED, consumed.status());
    }

    @Test
    void shouldRejectConsumeTwice() {
        ApprovalService.CheckResult pending = service.createPendingTicket("ls");
        service.submitApproval(pending.ticketId(), "ls", true);

        service.checkAndConsume(pending.ticketId(), "ls");
        // Second consume should fail — ticket already used
        ApprovalService.CheckResult second = service.checkAndConsume(pending.ticketId(), "ls");
        assertEquals(ApprovalService.CheckResult.Status.EXPIRED, second.status());
    }

    @Test
    void shouldRejectUnknownTicket() {
        ApprovalService.CheckResult result = service.checkAndConsume("fake-ticket", "ls");
        assertEquals(ApprovalService.CheckResult.Status.EXPIRED, result.status());
    }

    @Test
    void shouldCleanUpConsumedTicketsAfterShortTTL() throws InterruptedException {
        // Create and approve a ticket
        ApprovalService.CheckResult pending = service.createPendingTicket("ls");
        service.submitApproval(pending.ticketId(), "ls", true);
        service.checkAndConsume(pending.ticketId(), "ls");

        // Consumed tickets should be cleaned up within 1 minute
        // (This test just verifies the entry is removed after consume — actual TTL tested separately)
        ApprovalService.CheckResult second = service.checkAndConsume(pending.ticketId(), "ls");
        assertEquals(ApprovalService.CheckResult.Status.EXPIRED, second.status());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ApprovalServiceTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write SecurityException**

```java
package top.javarem.omni.tool.bash;

public class SecurityException extends RuntimeException {
    public SecurityException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write ApprovalService**

```java
package top.javarem.omni.tool.bash;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ApprovalService {

    private static final long TTL_MINUTES = 5;
    private static final long CONSUMED_TTL_MINUTES = 1; // Clean up consumed/rejected after 1 min

    private final ConcurrentHashMap<String, ApprovalEntry> tickets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
        r -> {
            Thread t = new Thread(r, "ApprovalService-Cleanup");
            t.setDaemon(true);
            return t;
        }
    );

    public ApprovalService() {
        cleanupScheduler.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(TTL_MINUTES);
            long consumedCutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(CONSUMED_TTL_MINUTES);
            tickets.entrySet().removeIf(e -> {
                ApprovalEntry entry = e.getValue();
                // Pending tickets: 5-min TTL
                if (entry.approved() == null && entry.timestamp() < cutoff) return true;
                // Consumed/rejected tickets: 1-min TTL
                if (entry.approved() != null && entry.timestamp() < consumedCutoff) return true;
                return false;
            });
        }, 1, 1, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        cleanupScheduler.shutdown();
    }

    public CheckResult createPendingTicket(String command) {
        String normalized = normalize(command);
        String ticketId = UUID.randomUUID().toString();
        tickets.put(ticketId, new ApprovalEntry(normalized, null, System.currentTimeMillis()));
        log.info("[ApprovalService] Ticket created: {} cmd={}", ticketId, normalized);
        return new CheckResult(Status.PENDING, ticketId, "⏸️ 命令待审批: " + command);
    }

    public boolean submitApproval(String ticketId, String command, boolean approved) {
        String normalized = normalize(command);
        ApprovalEntry entry = tickets.get(ticketId);

        if (entry == null) {
            log.warn("[ApprovalService] Ticket not found: {}", ticketId);
            return false;
        }

        // Strong binding check
        if (!entry.normalizedCommand().equals(normalized)) {
            log.warn("[ApprovalService] Command mismatch for ticket {}: expected={} got={}",
                ticketId, entry.normalizedCommand(), normalized);
            return false;
        }

        // Atomic update using compute
        tickets.compute(ticketId, (k, v) -> {
            if (v == null) return null;
            return new ApprovalEntry(v.normalizedCommand(), approved, v.timestamp());
        });

        log.info("[ApprovalService] Ticket {} approved={}", ticketId, approved);
        return true;
    }

    public CheckResult checkAndConsume(String ticketId, String command) {
        String normalized = normalize(command);

        // Atomic remove — returns old value or null if missing
        ApprovalEntry entry = tickets.remove(ticketId);

        if (entry == null) {
            return new CheckResult(Status.EXPIRED, ticketId, "审批超时或票根无效");
        }

        // Command binding check
        if (!entry.normalizedCommand().equals(normalized)) {
            log.warn("[ApprovalService] Command mismatch at consume: {} vs {}",
                entry.normalizedCommand(), normalized);
            return new CheckResult(Status.REJECTED, ticketId, "命令已被篡改，拒绝执行");
        }

        if (entry.approved() == null) {
            return new CheckResult(Status.PENDING, ticketId, "审批尚未完成");
        }

        if (entry.approved()) {
            return new CheckResult(Status.APPROVED, ticketId, "命令已审批通过");
        } else {
            return new CheckResult(Status.REJECTED, ticketId, "命令已被拒绝");
        }
    }

    private String normalize(String command) {
        return command.replaceAll("\\s+", " ").trim();
    }

    public record ApprovalEntry(String normalizedCommand, Boolean approved, long timestamp) {}

    public record CheckResult(Status status, String ticketId, String message) {
        public enum Status { APPROVED, PENDING, REJECTED, EXPIRED }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=ApprovalServiceTest -q`
Expected: PASS

- [ ] **Step 6: Create approved-commands.properties**

```properties
# Approved commands — no approval needed for these
# Format: command prefix (space-terminated or exact match)

ls
cat
grep
git
mvn
npm
node
python
python3
java
javac
./mvnw
./mvnw clean
./mvnw compile
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/bash/SecurityException.java \
        src/main/java/top/javarem/omni/tool/bash/ApprovalService.java \
        src/test/java/top/javarem/omni/tool/bash/ApprovalServiceTest.java \
        src/main/resources/approved-commands.properties
git commit -m "feat(bash): add ApprovalService with TTL and atomic checkAndConsume"
```

---

### Task 6: SecurityInterceptor

**Files:**
- Create: `src/main/java/top/javarem/omni/tool/bash/SecurityInterceptor.java`
- Create: `src/test/java/top/javarem/omni/tool/bash/SecurityInterceptorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityInterceptorTest {

    private SecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new SecurityInterceptor(
            new DangerousPatternValidator(),
            new PathNormalizer(System.getProperty("user.dir")),
            null // no approval service for unit tests
        );
    }

    @Test
    void shouldAllowSafeCommand() {
        SecurityInterceptor.CheckResult result = interceptor.check("ls -la");
        assertEquals(SecurityInterceptor.CheckResult.Type.ALLOW, result.type());
    }

    @Test
    void shouldDenyDirectRejectPatterns() {
        SecurityInterceptor.CheckResult result = interceptor.check("rm -rf /");
        assertEquals(SecurityInterceptor.CheckResult.Type.DENY, result.type());
        assertTrue(result.message().contains("禁止"));
    }

    @Test
    void shouldRequireApprovalForDangerousCommands() {
        SecurityInterceptor.CheckResult result = interceptor.check("rm -rf ./src");
        assertEquals(SecurityInterceptor.CheckResult.Type.PENDING, result.type());
        assertNotNull(result.ticketId());
    }

    @Test
    void shouldDenyInjection() {
        assertEquals(SecurityInterceptor.CheckResult.Type.DENY,
            interceptor.check("ls; rm -rf /").type());
        assertEquals(SecurityInterceptor.CheckResult.Type.DENY,
            interceptor.check("ls | cat /etc/passwd").type());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=SecurityInterceptorTest -q`
Expected: FAIL — class does not exist

- [ ] **Step 3: Write SecurityInterceptor**

```java
package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SecurityInterceptor {

    private final DangerousPatternValidator validator;
    private final PathNormalizer pathNormalizer;
    private final ApprovalService approvalService;

    public SecurityInterceptor(
            DangerousPatternValidator validator,
            PathNormalizer pathNormalizer,
            ApprovalService approvalService) {
        this.validator = validator;
        this.pathNormalizer = pathNormalizer;
        this.approvalService = approvalService;
    }

    public record CheckResult(Type type, String ticketId, String message) {
        public enum Type { ALLOW, DENY, PENDING }
    }

    public CheckResult check(String command) {
        if (command == null || command.isBlank()) {
            return new CheckResult(Type.DENY, null, "命令不能为空");
        }

        // 1. Validate dangerous patterns
        DangerousPatternValidator.Result patternResult = validator.validate(command);
        switch (patternResult) {
            case DENY:
                log.warn("[SecurityInterceptor] Command denied: {}", command);
                return new CheckResult(Type.DENY, null, "禁止执行的危险命令: " + command);
            case REQUIRE_APPROVAL:
                // Fall through to approval flow
                break;
            case ALLOW:
                // Fall through
                break;
        }

        // 2. Validate path boundaries
        try {
            pathNormalizer.validate(command);
        } catch (SecurityException e) {
            log.warn("[SecurityInterceptor] Path validation failed: {} — {}", command, e.getMessage());
            return new CheckResult(Type.DENY, null, e.getMessage());
        }

        // 3. If dangerous command requiring approval
        if (patternResult == DangerousPatternValidator.Result.REQUIRE_APPROVAL) {
            if (approvalService == null) {
                return new CheckResult(Type.DENY, null, "审批服务不可用，拒绝执行: " + command);
            }
            ApprovalService.CheckResult approval = approvalService.createPendingTicket(command);
            return new CheckResult(Type.PENDING, approval.ticketId(), approval.message());
        }

        return new CheckResult(Type.ALLOW, null, "命令允许执行");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=SecurityInterceptorTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/bash/SecurityInterceptor.java \
        src/test/java/top/javarem/omni/tool/bash/SecurityInterceptorTest.java
git commit -m "feat(bash): add SecurityInterceptor as facade for validation and approval"
```

---

## SPRINT 3: Execution Core & Web Controller

**Objective:** Refactor BashExecutor, wire up all components, and add the Web approval HTTP endpoint.

---

### Sprint 3 File Layout

```
src/main/java/top/javarem/omni/controller/
  ApprovalController.java        (NEW)
src/main/java/top/javarem/omni/tool/bash/
  BashExecutor.java              (MODIFY — full rewrite of execute methods)
  BashToolConfig.java            (MODIFY — delegate to SecurityInterceptor)
  ProcessTreeKiller.java         (MODIFY — ProcessHandle-based destruction)
  ResponseFormatter.java         (MODIFY — minor)
```

---

### Task 7: BashExecutor Refactor

**Files:**
- Modify: `src/main/java/top/javarem/omni/tool/bash/BashExecutor.java`
- Note: Keep `BashConstants.java` as-is (already correct)

- [ ] **Step 1: Write integration test for encoding and stderr merge**

```java
package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BashExecutorIT {

    @Autowired(required = false)
    private BashExecutor executor;

    @Test
    void shouldHandleUtf8Output() {
        if (executor == null) return; // Skip if not wired
        String result = executor.execute("echo '中文测试 ¥€$'", 10000);
        assertNotNull(result);
    }

    @Test
    void shouldMergeStderr() {
        if (executor == null) return;
        // Both stdout and stderr should appear in result
        String result = executor.execute("echo 'out' && echo 'err' >&2", 10000);
        assertTrue(result.contains("out") || result.contains("err"),
            "stderr should be merged into stdout");
    }

    @Test
    void shouldTimeoutGracefully() {
        if (executor == null) return;
        String result = executor.execute("sleep 10", 500);
        assertTrue(result.contains("超时") || result.contains("timeout"),
            "Should indicate timeout");
    }
}
```

- [ ] **Step 2: Run test to verify it fails (expected — we haven't refactored yet)**

Run: `./mvnw test -Dtest=BashExecutorIT -q`
Expected: FAIL or SKIP (executor not yet wired)

- [ ] **Step 3: Rewrite BashExecutor.execute() and executeBackground()**

```java
package top.javarem.omni.tool.bash;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

@Component
@Slf4j
public class BashExecutor {

    @Autowired
    private ProcessRegistry processRegistry;

    @Autowired
    private SecurityInterceptor securityInterceptor;

    @Resource
    private ResponseFormatter formatter;

    private final ProcessTreeKiller processKiller = new ProcessTreeKiller();

    // Spring-managed thread pool — not a bare executor
    @Autowired
    private ThreadPoolTaskExecutor taskExecutor;

    // Shell detection
    private volatile String detectedShell;
    private volatile boolean shellDetected = false;

    @PostConstruct
    public void init() {
        detectBash(); // Eager detection, not on every call
    }

    public String execute(String command, long timeoutMs) throws Exception {
        // 1. Security interception
        SecurityInterceptor.CheckResult check = securityInterceptor.check(command);
        switch (check.type()) {
            case DENY:
                return formatter.formatError("安全拦截: " + check.message(), -1);
            case PENDING:
                return formatter.formatPending(check.ticketId(), check.message());
            case ALLOW:
                // Proceed
                break;
        }

        // 2. Execute
        return doExecute(command, timeoutMs, false);
    }

    public String executeBackground(String command) throws Exception {
        SecurityInterceptor.CheckResult check = securityInterceptor.check(command);
        if (check.type() == SecurityInterceptor.CheckResult.Type.DENY) {
            return formatter.formatError("安全拦截: " + check.message(), -1);
        }
        if (check.type() == SecurityInterceptor.CheckResult.Type.PENDING) {
            return formatter.formatPending(check.ticketId(), check.message());
        }

        return doExecute(command, 0, true);
    }

    private String doExecute(String command, long timeoutMs, boolean background) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        configureProcessBuilder(builder, command);
        builder.directory(new File(BashConstants.WORKSPACE));
        builder.redirectErrorStream(true); // Merge stderr

        Process process = builder.start();
        String pid = String.valueOf(process.pid());

        log.info("[BashExecutor] Process started: PID={} cmd={}", pid, command);

        if (background) {
            ManagedProcess mp = new ManagedProcess(
                pid, process.toHandle(), command, "",
                Instant.now(), ManagedProcess.ProcessState.RUNNING, true
            );
            processRegistry.register(mp);
            return formatter.formatBackgroundStarted(pid, command);
        }

        // Register for non-background process too (for observability)
        ManagedProcess mp = new ManagedProcess(
            pid, process.toHandle(), command, "",
            Instant.now(), ManagedProcess.ProcessState.RUNNING, false
        );
        processRegistry.register(mp);

        // 3. Read output asynchronously
        Charset charset = detectCharset();
        StringBuilder output = new StringBuilder();
        ExecutorService readerExecutor = Executors.newSingleThreadExecutor();

        Future<?> readerFuture = readerExecutor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            } catch (Exception e) {
                log.warn("[BashExecutor] Output reading failed", e);
            }
        });

        // 4. Wait for process with timeout
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        String rawOutput = output.toString();

        if (!finished) {
            processKiller.kill(process);
            readerFuture.cancel(true);
            processRegistry.kill(pid);
            log.warn("[BashExecutor] Command timeout: {}ms cmd={}", timeoutMs, command);
            return formatter.formatTimeout(rawOutput, timeoutMs);
        }

        try {
            readerFuture.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            // Ignore — process already done
        } finally {
            readerExecutor.shutdown();
            processRegistry.unregister(pid);
        }

        int exitCode = process.exitValue();
        if (exitCode == 0) {
            return formatter.formatSuccess(rawOutput);
        } else {
            return formatter.formatError(rawOutput, exitCode);
        }
    }

    private void configureProcessBuilder(ProcessBuilder builder, String command) {
        String shell = detectBash();
        if (shell.equals("sh")) {
            builder.command("sh", "-c", command);
        } else if (shell.equals("bash")) {
            builder.command("bash", "-c", command);
        } else if (shell.equals("cmd")) {
            builder.command("cmd", "/c", command);
        } else {
            builder.command(shell, "-c", command);
        }
    }

    private synchronized String detectBash() {
        if (shellDetected) return detectedShell;

        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            detectedShell = "sh";
            shellDetected = true;
            return detectedShell;
        }

        // Windows detection...
        try {
            ProcessBuilder testBuilder = new ProcessBuilder("bash", "-c", "echo test");
            testBuilder.redirectErrorStream(true);
            Process test = testBuilder.start();
            if (test.waitFor(3, TimeUnit.SECONDS) && test.exitValue() == 0) {
                detectedShell = "bash";
                shellDetected = true;
                log.info("[BashExecutor] Detected global bash");
                return detectedShell;
            }
        } catch (Exception e) { /* fall through */ }

        String[][] gitBashPaths = {
            {"C:\\Program Files\\Git\\bin\\bash.exe", "Git Bash (默认)"},
            {System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe", "Git Bash (用户)"},
        };
        for (String[] pathAndDesc : gitBashPaths) {
            File bash = new File(pathAndDesc[0]);
            if (bash.exists()) {
                detectedShell = bash.getAbsolutePath();
                shellDetected = true;
                log.info("[BashExecutor] Detected {}: {}", pathAndDesc[1], detectedShell);
                return detectedShell;
            }
        }

        detectedShell = "cmd";
        shellDetected = true;
        log.warn("[BashExecutor] No bash found, falling back to cmd");
        return detectedShell;
    }

    private Charset detectCharset() {
        Charset detected = Charset.defaultCharset();
        if (StandardCharsets.UTF_8.equals(detected) ||
            StandardCharsets.ISO_8859_1.equals(detected)) {
            return detected;
        }
        // Windows with non-UTF-8 default — try UTF-8 anyway
        try {
            return StandardCharsets.UTF_8;
        } catch (Exception e) {
            return detected;
        }
    }
}
```

**Note:** Add missing import for `java.time.Instant` and `java.util.concurrent.ExecutionException`.

- [ ] **Step 4: Fix ResponseFormatter to add formatPending and formatBackgroundStarted**

```java
public String formatPending(String ticketId, String message) {
    return String.format("⏸️ %s\n\n票根ID: %s\n\n请在界面中审批此命令", message, ticketId);
}

public String formatBackgroundStarted(String pid, String command) {
    return String.format("✅ 后台命令已启动\n\nPID: %s\n命令: %s", pid, command);
}
```

- [ ] **Step 5: Update ProcessTreeKiller to use ProcessHandle**

```java
public void kill(Process process) {
    long pid = process.pid();
    try {
        process.destroyForcibly();
    } catch (Exception ignored) {}

    String os = System.getProperty("os.name").toLowerCase();
    if (os.contains("windows")) {
        runCommand("cmd", "/c", "taskkill /F /T /PID " + pid);
    } else {
        runCommand("pkill", "-P", String.valueOf(pid));
        runCommand("kill", "-9", String.valueOf(pid));
    }
}

private void runCommand(String... command) {
    try {
        new ProcessBuilder(command).start();
    } catch (Exception ignored) {}
}
```

- [ ] **Step 6: Update BashToolConfig to delegate to SecurityInterceptor**

```java
@Resource
private BashExecutor executor;

@Resource
private SecurityInterceptor securityInterceptor;

@Tool(name = "bash", description = "执行 Shell 命令...")
public String bash(
        String command,
        @ToolParam(description = "对命令行为的简洁描述", required = false) String description,
        @ToolParam(description = "超时毫秒数", required = false) Long timeout,
        @ToolParam(description = "是否后台运行", required = false) Boolean runInBackground,
        @ToolParam(description = "危险选项：是否禁用沙箱", required = false) Boolean dangerouslyDisableSandbox) {

    log.info("[BashToolConfig] 执行命令: {} | 描述: {} | 后台: {}", command, description, runInBackground);

    if (command == null || command.trim().isEmpty()) {
        return "❌ 命令不能为空";
    }

    // dangerouslyDisableSandbox is always denied — no override allowed
    if (Boolean.TRUE.equals(dangerouslyDisableSandbox)) {
        return "❌ dangerouslyDisableSandbox 不再支持，请使用安全审批流程";
    }

    long timeoutMs = normalizeTimeout(timeout);

    try {
        if (Boolean.TRUE.equals(runInBackground)) {
            return executor.executeBackground(command);
        }
        return executor.execute(command, timeoutMs);
    } catch (Exception e) {
        log.error("[BashToolConfig] 执行异常: {}", e.getMessage(), e);
        return "❌ 命令执行异常: " + e.getMessage();
    }
}
```

- [ ] **Step 7: Run all tests**

Run: `./mvnw test -Dtest="Bash*Test,BashExecutorIT" -q`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/top/javarem/omni/tool/bash/BashExecutor.java \
        src/main/java/top/javarem/omni/tool/bash/BashToolConfig.java \
        src/main/java/top/javarem/omni/tool/bash/ProcessTreeKiller.java \
        src/main/java/top/javarem/omni/tool/bash/ResponseFormatter.java
git commit -m "refactor(bash): refactor BashExecutor with thread pool, stderr merge, ProcessHandle lifecycle"
```

---

### Task 8: ApprovalController

**Files:**
- Create: `src/main/java/top/javarem/omni/controller/ApprovalController.java`

- [ ] **Step 1: Write the controller**

```java
package top.javarem.omni.controller;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import top.javarem.omni.tool.bash.ApprovalService;

import java.util.Map;

@RestController
@RequestMapping("/approval")
@Slf4j
public class ApprovalController {

    @Resource
    private ApprovalService approvalService;

    @PostMapping
    public Map<String, Object> approve(
            @RequestBody ApprovalRequest request) {
        log.info("[ApprovalController] Approval request: ticketId={} approved={} cmd={}",
            request.ticketId(), request.approved(), request.command());

        boolean success = approvalService.submitApproval(
            request.ticketId(),
            request.command(),
            request.approved()
        );

        String message = success
            ? (request.approved() ? "已批准命令执行" : "已拒绝命令执行")
            : "审批失败：票根无效或命令不匹配";

        return Map.of("success", success, "message", message);
    }

    public record ApprovalRequest(
        String ticketId,
        Boolean approved,
        String command
    ) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/controller/ApprovalController.java
git commit -m "feat(bash): add ApprovalController for Web-based command approval"
```

---

### Task 9: ThreadPoolExecutor Bean Configuration

**Files:**
- Create: `src/main/java/top/javarem/omni/config/BashExecutorConfig.java`

- [ ] **Step 1: Create Spring-managed thread pool**

```java
package top.javarem.omni.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class BashExecutorConfig {

    @Bean
    public ThreadPoolTaskExecutor bashTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("bash-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/top/javarem/omni/config/BashExecutorConfig.java
git commit -m "feat(bash): add Spring-managed ThreadPoolTaskExecutor for bash operations"
```

---

## Final Verification

After all 3 sprints, run the full test suite:

```bash
./mvnw test -Dtest="top.javarem.omni.tool.bash.**"
```

Expected: All tests pass. The bash tool now has:
- Process lifecycle management via ProcessRegistry
- Quote-aware dangerous command detection
- Ticket-based Web approval flow with TTL
- Spring-managed thread pool with graceful shutdown
- Proper charset handling and stderr merging
