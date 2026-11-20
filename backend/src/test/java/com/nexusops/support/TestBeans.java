package com.nexusops.support;

import com.nexusops.ai.AiIndexExecutorConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    RecordingMailSender recordingMailSender() {
        return new RecordingMailSender();
    }

    @Bean
    @Primary
    FakeAiClient fakeAiClient() {
        return new FakeAiClient();
    }

    /** Re-indexing after a publish/edit runs inline in tests, so the chunks exist when the API call returns. */
    @Bean
    @Primary
    @Qualifier(AiIndexExecutorConfig.AI_INDEX_EXECUTOR)
    SyncTaskExecutor syncAiIndexExecutor() {
        return new SyncTaskExecutor();
    }
}
