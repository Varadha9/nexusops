package com.nexusops.ai;

/** ai-service could not be reached, timed out, or answered with an error or an unusable body (D12). */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
