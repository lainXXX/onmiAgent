package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import static org.junit.jupiter.api.Assertions.*;

class DangerousPatternValidatorTest {

    private DangerousPatternValidator validator;

    @BeforeEach
    void setUp() {
        Resource resource = new ClassPathResource("test-commands.properties");
        validator = new DangerousPatternValidator(resource);
    }

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
        assertEquals(DangerousPatternValidator.Result.DENY,
            validator.validate("echo \"test\" | rm -rf /"));
    }
}