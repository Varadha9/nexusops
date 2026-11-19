package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagerChainTest {

    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();

    @Test
    void nobodyManagesThemselves() {
        assertThat(ManagerChain.createsLoop(a, a, Map.of())).isTrue();
    }

    @Test
    void aLoopThroughSeveralLevelsIsFound() {
        Map<UUID, UUID> managerOf = new HashMap<>();
        managerOf.put(a, b); // a reports to b
        managerOf.put(b, c); // b reports to c
        assertThat(ManagerChain.createsLoop(c, a, managerOf)).isTrue(); // c reporting to a closes the loop
        assertThat(ManagerChain.createsLoop(c, b, managerOf)).isTrue();
    }

    @Test
    void anOrdinaryChainIsFine() {
        Map<UUID, UUID> managerOf = Map.of(a, b);
        assertThat(ManagerChain.createsLoop(c, a, managerOf)).isFalse();
        assertThat(ManagerChain.createsLoop(a, c, managerOf)).isFalse();
        assertThat(ManagerChain.createsLoop(a, null, managerOf)).isFalse();
    }

    @Test
    void anExistingLoopInTheDataDoesNotHangTheCheck() {
        Map<UUID, UUID> broken = Map.of(a, b, b, a);
        assertThat(ManagerChain.createsLoop(c, a, broken)).isFalse();
    }
}
