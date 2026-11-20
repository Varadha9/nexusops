package com.nexusops.hrms;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.util.UUID;

public record PolicyView(UUID id, String title, String body, PolicyStatus status, MemberRef author, Instant publishedAt,
        Instant createdAt, Instant updatedAt, long version) {}
