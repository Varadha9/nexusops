package com.nexusops.hrms;

import java.util.UUID;

public record PersonRef(UUID id, String name, String email, String phone) {}
