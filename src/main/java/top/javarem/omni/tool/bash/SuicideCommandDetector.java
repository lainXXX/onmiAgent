package top.javarem.omni.tool.bash;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 防自杀检测器
 * 检测命令是否试图终止当前进程自身
 */
@Slf4j
public class SuicideCommandDetector {

    private static final Pattern TASKKILL_PID_PATTERN =
            Pattern.compile("taskkill\\s+/?[fF]?\\s*/?[pP][iI][dD]\\s+(\\d+)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern WMIC_PID_PATTERN =
            Pattern.compile("processid\\s*=\\s*\\d+", Pattern.CASE_INSENSITIVE);

    private static final Pattern KILL_PID_PATTERN =
            Pattern.compile("(?:stop-process|kill|pkill)[^\\d]*\\d+", Pattern.CASE_INSENSITIVE);

    /**
     * 检测命令是否试图终止当前进程
     */
    public boolean isSuicide(String command) {
        String lower = command.toLowerCase();
        long currentPid = BashConstants.CURRENT_PID;

        // taskkill /F /PID <数字>
        if (lower.contains("taskkill")) {
            var matcher = TASKKILL_PID_PATTERN.matcher(command);
            while (matcher.find()) {
                try {
                    long targetPid = Long.parseLong(matcher.group(1));
                    if (targetPid == currentPid) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // wmic process where processid=<pid> delete
        if (lower.contains("wmic") && lower.contains("processid")) {
            if (WMIC_PID_PATTERN.matcher(command).find()) {
                Pattern pidPattern = Pattern.compile("processid\\s*=\\s*" + currentPid, Pattern.CASE_INSENSITIVE);
                if (pidPattern.matcher(command).find()) {
                    return true;
                }
            }
        }

        // Stop-Process / Kill / Pkill <pid>
        if (lower.contains("stop-process") || lower.contains("kill") || lower.contains("pkill")) {
            Pattern pidPattern = Pattern.compile("(?:stop-process|kill|pkill)[^\\d]*" + currentPid, Pattern.CASE_INSENSITIVE);
            if (pidPattern.matcher(command).find()) {
                return true;
            }
        }

        return false;
    }
}
