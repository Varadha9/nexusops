package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillGapsTest {

    final UUID negotiation = UUID.randomUUID();
    final UUID excel = UUID.randomUUID();
    final UUID crm = UUID.randomUUID();

    @Test
    void missingAndBelowLevelSkillsAreGapsInTheRolesOrder() {
        Map<UUID, Integer> required = new LinkedHashMap<>();
        required.put(negotiation, 4);
        required.put(excel, 2);
        required.put(crm, 3);
        Map<UUID, Integer> held = Map.of(negotiation, 2, excel, 5);
        assertThat(SkillGaps.gaps(required, held)).containsExactly(
                new SkillGaps.Gap(negotiation, 4, 2),
                new SkillGaps.Gap(crm, 3, null));
    }

    @Test
    void meetingEveryRequirementLeavesNoGaps() {
        assertThat(SkillGaps.gaps(Map.of(excel, 3), Map.of(excel, 3))).isEmpty();
        assertThat(SkillGaps.gaps(Map.of(), Map.of(excel, 1))).isEmpty();
    }
}
