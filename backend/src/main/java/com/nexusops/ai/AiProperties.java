package com.nexusops.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Where ai-service listens ({@code nexusops.ai.service-url}, env {@code AI_SERVICE_URL}). */
@ConfigurationProperties("nexusops.ai")
public record AiProperties(@DefaultValue("http://localhost:8000") String serviceUrl) {}
