package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import static org.junit.jupiter.api.Assertions.*;

class SecurityInterceptorTest {

    private SecurityInterceptor interceptor;

    @BeforeEach
    void setUp() {
        Resource resource = new ClassPathResource("test-commands.properties");
        interceptor = new SecurityInterceptor(
            new DangerousPatternValidator(resource),
            new PathNormalizer(System.getProperty("user.dir")),
            null
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
    void shouldDenyDangerousWhenApprovalServiceNull() {
        // With null approvalService, dangerous commands must be denied
        var r = interceptor.check("rm -rf ./src");
        assertEquals(SecurityInterceptor.CheckResult.Type.DENY, r.type());
    }

    @Test
    void shouldRequireApprovalForDangerousCommands() {
        // With real ApprovalService, should return PENDING
        Resource resource = new ClassPathResource("test-commands.properties");
        var svc = new ApprovalService();
        var si = new SecurityInterceptor(new DangerousPatternValidator(resource), new PathNormalizer(System.getProperty("user.dir")), svc);
        var r = si.check("rm -rf ./src");
        assertEquals(SecurityInterceptor.CheckResult.Type.PENDING, r.type());
        assertNotNull(r.ticketId());
        svc.shutdown();
    }

    @Test
    void shouldDenyInjection() {
        assertEquals(SecurityInterceptor.CheckResult.Type.DENY,
            interceptor.check("ls; rm -rf /").type());
        assertEquals(SecurityInterceptor.CheckResult.Type.DENY,
            interceptor.check("ls | cat /etc/passwd").type());
    }
}