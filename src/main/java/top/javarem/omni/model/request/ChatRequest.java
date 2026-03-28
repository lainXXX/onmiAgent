package top.javarem.onmi.model.request;

import lombok.Data;

@Data
public class ChatRequest {
        private String question;
        private String sessionId;
}