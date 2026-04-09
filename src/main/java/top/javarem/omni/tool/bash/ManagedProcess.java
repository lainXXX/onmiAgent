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
            if (this.state == ManagedProcess.ProcessState.KILLED
                    || this.state == ManagedProcess.ProcessState.TERMINATED) return;
            this.state = newState;
        }
    }
}