package com.nexusops.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Re-embedding after a knowledge change runs off the request thread on a small bounded pool: ai-service latency never
 * delays the publish/edit response or holds the request's database connection. When the queue is full the change is
 * dropped with a warning (the source stays un-indexed until its next change or a rebuild, as D3 allows); the caller
 * never sees an error. Tests replace it with a synchronous executor (TestBeans) so indexing is immediate.
 */
@Configuration(proxyBeanMethods = false)
public class AiIndexExecutorConfig {

    public static final String AI_INDEX_EXECUTOR = "aiIndexExecutor";
    private static final Logger log = LoggerFactory.getLogger(AiIndexExecutorConfig.class);

    @Bean(name = AI_INDEX_EXECUTOR)
    public TaskExecutor aiIndexExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-index-");
        executor.setRejectedExecutionHandler((task, pool) -> log.warn(
                "AI index queue is full; a knowledge change was not re-indexed (rebuild the index to catch up)"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
