package com.nexusops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.knowledge.KnowledgeChanged;
import com.nexusops.shared.knowledge.KnowledgeDocument;
import com.nexusops.shared.knowledge.KnowledgeSource;
import com.nexusops.support.FakeAiClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/** The after-commit re-embed is handed off the request thread, with the tenant carried across. */
class KnowledgeIndexExecutorTest {

    final UUID tenant = UUID.randomUUID();
    final AtomicReference<Optional<UUID>> tenantSeenBySource = new AtomicReference<>();
    final FakeAiClient ai = new FakeAiClient();
    final List<Runnable> queued = new ArrayList<>();

    final KnowledgeSource source = new KnowledgeSource() {
        @Override
        public String sourceType() {
            return "KB_ARTICLE";
        }

        @Override
        public List<KnowledgeDocument> published() {
            return List.of();
        }

        @Override
        public Optional<KnowledgeDocument> findPublished(UUID id) {
            tenantSeenBySource.set(TenantContext.tenantId());
            return Optional.empty(); // nothing to embed or write
        }
    };

    final KnowledgeIndex index = new KnowledgeIndex(mock(JdbcTemplate.class), ai, mock(TenantLocks.class),
            List.of(source), mock(PlatformTransactionManager.class), queued::add);

    @Test
    void theListenerOnlyQueuesTheWorkAndTheWorkRunsAsTheTenant() throws Exception {
        TenantContext.runAs(tenant, () -> index.reindex(new KnowledgeChanged("KB_ARTICLE", UUID.randomUUID())));
        assertThat(queued).hasSize(1);
        assertThat(tenantSeenBySource.get()).isNull(); // nothing ran on the calling thread

        Thread worker = Thread.ofPlatform().start(queued.getFirst());
        worker.join(5_000);
        assertThat(tenantSeenBySource.get()).contains(tenant);
        assertThat(TenantContext.tenantId()).isEmpty();
    }

    @Test
    void withoutATenantNothingIsQueued() {
        index.reindex(new KnowledgeChanged("KB_ARTICLE", UUID.randomUUID()));
        assertThat(queued).isEmpty();
    }

    @Test
    void aFailingExecutorNeverReachesTheCaller() {
        KnowledgeIndex rejecting = new KnowledgeIndex(mock(JdbcTemplate.class), ai, mock(TenantLocks.class),
                List.of(source), mock(PlatformTransactionManager.class), task -> {
                    throw new IllegalStateException("rejected");
                });
        KnowledgeChanged event = new KnowledgeChanged("KB_ARTICLE", UUID.randomUUID());
        assertThatCode(() -> TenantContext.runAs(tenant, () -> rejecting.reindex(event))).doesNotThrowAnyException();
    }

    @Test
    void theProductionExecutorIsASmallBoundedPoolThatDropsOverflowQuietly() throws Exception {
        TaskExecutor executor = new AiIndexExecutorConfig().aiIndexExecutor();
        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        try {
            assertThat(pool.getCorePoolSize()).isEqualTo(2);
            assertThat(pool.getMaxPoolSize()).isEqualTo(2);
            assertThat(pool.getQueueCapacity()).isEqualTo(100);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(2);
            AtomicReference<String> thread = new AtomicReference<>();
            Runnable blocked = () -> {
                thread.compareAndSet(null, Thread.currentThread().getName());
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            // 2 running + 100 queued fill it; the rest are dropped without an exception
            assertThatCode(() -> {
                for (int i = 0; i < 110; i++) {
                    executor.execute(blocked);
                }
            }).doesNotThrowAnyException();
            assertThat(pool.getThreadPoolExecutor().getQueue()).hasSize(100);
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(thread.get()).startsWith("ai-index-");
            release.countDown();
        } finally {
            pool.shutdown();
        }
    }
}
