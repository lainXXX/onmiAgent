package top.javarem.omni.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * SSE 异常处理
 * 捕获客户端断开连接时的 IOException，避免不必要的 ERROR 日志
 */
@ControllerAdvice
@Slf4j
public class SseExceptionHandler {

    @ExceptionHandler(IOException.class)
    public void handleIOException(IOException e, HttpServletResponse response) {
        // 客户端断开连接导致的 IOException 是正常行为，不记录 error
        // 这通常发生在：用户关闭页面、刷新、网络中断等
        log.debug("[SseException] 客户端断开连接（正常）: {}", e.getMessage());
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException e, HttpServletResponse response) throws IOException {
        // SSE 超时，尝试发送超时事件
        log.debug("[SseException] SSE 超时: {}", e.getMessage());
    }
}
