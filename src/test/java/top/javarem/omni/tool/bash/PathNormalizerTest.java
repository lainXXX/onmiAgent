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
        String normalized = normalizer.normalize("D:\\workspace\\src\\main");
        assertEquals("D:/workspace/src/main", normalized);
    }

    @Test
    void shouldConvertGitBashPathToWindows() {
        // /c/Users/... -> C:/Users/...
        String normalized = normalizer.normalize("/c/Users/test/workspace");
        assertEquals("C:/Users/test/workspace", normalized);
    }

    @Test
    void shouldAllowGitBashPathInsideWorkspace() {
        // Git Bash path within workspace should be allowed
        // Note: This only works if workspace is on C: drive since /c/ maps to C:
        PathNormalizer cDriveNormalizer = new PathNormalizer("C:/workspace");
        assertDoesNotThrow(() -> cDriveNormalizer.validate("/c/workspace/src/main"));
    }

    @Test
    void shouldRejectGitBashPathOutsideWorkspace() {
        // Git Bash path outside workspace should be rejected
        PathNormalizer cDriveNormalizer = new PathNormalizer("C:/workspace");
        SecurityException ex = assertThrows(SecurityException.class,
            () -> cDriveNormalizer.validate("/c/etc/passwd"));
        assertTrue(ex.getMessage().contains("WORKSPACE"));
    }
}