package com.nexusops.hrms;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reporting lines must not loop (D2): nobody may end up, directly or through others, managing themselves. */
final class ManagerChain {

    private ManagerChain() {}

    /** True when making {@code managerId} the manager of {@code employeeId} would close a loop. */
    static boolean createsLoop(UUID employeeId, UUID managerId, Map<UUID, UUID> managerOf) {
        Set<UUID> seen = new HashSet<>();
        UUID current = managerId;
        while (current != null && seen.add(current)) {
            if (current.equals(employeeId)) {
                return true;
            }
            current = managerOf.get(current);
        }
        return false;
    }
}
