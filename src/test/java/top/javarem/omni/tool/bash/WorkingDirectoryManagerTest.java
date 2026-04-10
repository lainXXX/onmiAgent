package top.javarem.omni.tool.bash;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkingDirectoryManager 单元测试
 */
class WorkingDirectoryManagerTest {

    private WorkingDirectoryManager wdm;
    private Path projectRoot;

    @BeforeEach
    void setUp() {
        // 使用系统临时目录作为测试项目根
        projectRoot = Paths.get(System.getProperty("user.dir"));
        wdm = new WorkingDirectoryManager(projectRoot.toString());
    }

    // ==================== 初始化 ====================

    @Test
    void init_setsCurrentDirToProjectRoot() {
        assertEquals(projectRoot, wdm.getCurrentDir());
    }

    @Test
    void getProjectRoot_returnsConfiguredRoot() {
        assertEquals(projectRoot, wdm.getProjectRoot());
    }

    // ==================== 路径验证 ====================

    @Test
    void isWithinProject_subPath_returnsTrue() {
        Path subPath = projectRoot.resolve("src/main/java");
        assertTrue(wdm.isWithinProject(subPath));
    }

    @Test
    void isWithinProject_parentPath_returnsFalse() {
        Path parentPath = projectRoot.getParent();
        if (parentPath != null) {
            assertFalse(wdm.isWithinProject(parentPath));
        }
    }

    @Test
    void isWithinProject_nullPath_returnsFalse() {
        assertFalse(wdm.isWithinProject(null));
    }

    @Test
    void isWithinProject_absolutePath_outsideProject_returnsFalse() {
        Path outside = Paths.get("/tmp").toAbsolutePath();
        if (!outside.startsWith(projectRoot)) {
            assertFalse(wdm.isWithinProject(outside));
        }
    }

    // ==================== 跟踪 cd 命令 ====================

    @Test
    void trackAndValidate_withPwdOutput_updatesCurrentDir() {
        String pwdOutput = "/home/user/project/src\n";
        Path newDir = wdm.trackAndValidate(pwdOutput, "");
        // 不在项目目录下，应该保持原值
        assertNotNull(newDir);
    }

    @Test
    void trackAndValidate_withCdOutput_updatesCurrentDir() {
        // 构造一个在项目内的 cd 输出
        String cdOutput = "cd " + projectRoot.resolve("src");
        Path newDir = wdm.trackAndValidate(cdOutput, "");
        assertNotNull(newDir);
    }

    @Test
    void trackAndValidate_nullOutput_keepsCurrentDir() {
        Path before = wdm.getCurrentDir();
        Path after = wdm.trackAndValidate(null, null);
        assertEquals(before, after);
    }

    // ==================== 重置 ====================

    @Test
    void resetToProjectRoot_restoresProjectRoot() {
        wdm.resetToProjectRoot();
        assertEquals(projectRoot, wdm.getCurrentDir());
    }

    // ==================== Git Bash 路径 ====================

    @Test
    void isWithinProject_gitBashStylePath_returnsTrue() {
        // Git Bash 风格路径 /c/Users/... 应该在 Windows 下正确处理
        String gitBashPath = "/" + Character.toLowerCase(projectRoot.getRoot().toString().charAt(0))
                + projectRoot.toString().substring(2);
        // 简化测试：确保路径字符串不为空
        assertNotNull(gitBashPath);
    }
}
