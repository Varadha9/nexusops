package com.nexusops.hrms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The deterministic skill-gap rule (D8): a required skill held below its minimum level, or not at all. */
final class SkillGaps {

    record Gap(UUID skillId, int required, Integer held) {}

    private SkillGaps() {}

    static List<Gap> gaps(Map<UUID, Integer> required, Map<UUID, Integer> held) {
        List<Gap> gaps = new ArrayList<>();
        required.forEach((skillId, minimum) -> {
            Integer level = held.get(skillId);
            if (level == null || level < minimum) {
                gaps.add(new Gap(skillId, minimum, level));
            }
        });
        return gaps;
    }
}
