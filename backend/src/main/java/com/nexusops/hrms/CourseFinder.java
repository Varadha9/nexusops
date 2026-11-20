package com.nexusops.hrms;

import java.util.List;
import java.util.UUID;

interface CourseFinder {
    List<CourseRef> teaching(UUID skillId, int minimumLevel);
}
