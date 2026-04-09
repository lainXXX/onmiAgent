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

    @Test
    void shouldNotTransitionFromKilledState() {
        ManagedProcess mp = new ManagedProcess(
            "999", ProcessHandle.current(), "sleep 10", "sleep",
            Instant.now(), ManagedProcess.ProcessState.RUNNING, false
        );

        mp.transitionTo(ManagedProcess.ProcessState.KILLED);
        assertEquals(ManagedProcess.ProcessState.KILLED, mp.state());

        // Should NOT transition to TERMINATED — KILLED is terminal
        mp.transitionTo(ManagedProcess.ProcessState.TERMINATED);
        assertEquals(ManagedProcess.ProcessState.KILLED, mp.state());
    }
}