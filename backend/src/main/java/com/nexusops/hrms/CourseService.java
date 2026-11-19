package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.hrms.domain.Course;
import com.nexusops.hrms.domain.CourseRepository;
import com.nexusops.hrms.domain.CourseSkill;
import com.nexusops.hrms.domain.CourseSkillRepository;
import com.nexusops.hrms.domain.Skill;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.web.ApiProblem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Courses and the skills they teach (D9); also recommends courses for skill gaps. */
@Service
public class CourseService implements CourseFinder {

    static final String UNKNOWN = "Choose a course in this workspace.";
    static final String LEVEL = "Choose a level from 1 to 5.";
    static final String REPEATED = "Each skill can be listed once.";
    static final String DURATION = "Use more than 0 and at most 999 hours.";
    private static final BigDecimal MAX_BEFORE_ROUNDING = new BigDecimal("999.05");
    static final String LINK = "Use a web address starting with http:// or https://.";

    private final CourseRepository courses;
    private final CourseSkillRepository taughtSkills;
    private final SkillService skills;
    private final AuditService audit;

    CourseService(CourseRepository courses, CourseSkillRepository taughtSkills, SkillService skills,
            AuditService audit) {
        this.courses = courses;
        this.taughtSkills = taughtSkills;
        this.skills = skills;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<CourseView> list(boolean archived) {
        TenantContext.requireTenantId();
        return views(archived ? courses.findByArchivedAtIsNotNullOrderByTitleAsc()
                : courses.findByArchivedAtIsNullOrderByTitleAsc());
    }

    @Transactional(readOnly = true)
    public CourseView get(UUID id) {
        return views(List.of(find(id))).getFirst();
    }

    @Transactional
    public CourseView create(CourseCommand command) {
        TenantContext.requireTenantId();
        Draft d = validate(command, null);
        Course course = courses.saveAndFlush(new Course(Ids.newId(), d.title(), d.description(), d.durationHours(),
                d.link()));
        replaceSkills(course.getId(), d.taught());
        audit.record(AuditEntry.of("CourseCreated", "Course", course.getId()).withAfter(snapshot(course, d.taught())));
        return views(List.of(course)).getFirst();
    }

    @Transactional
    public CourseView update(UUID id, CourseCommand command, Long version) {
        Course course = find(id);
        HrRecords.checkVersion(course.getVersion(), version);
        Draft d = validate(command, course);
        HrRecords.requireNotArchived(course.isArchived());
        Map<String, Object> before = snapshot(course, taught(id));
        course.apply(d.title(), d.description(), d.durationHours(), d.link());
        courses.flush();
        replaceSkills(id, d.taught());
        audit.record(AuditEntry.of("CourseUpdated", "Course", id).withBefore(before)
                .withAfter(snapshot(course, d.taught())));
        return views(List.of(course)).getFirst();
    }

    @Transactional
    public CourseView archive(UUID id) {
        Course course = find(id);
        if (!course.isArchived()) {
            course.archive(Instant.now());
            courses.flush();
            audit.record(AuditEntry.of("CourseArchived", "Course", id).withBefore(Map.of("title", course.getTitle())));
        }
        return views(List.of(course)).getFirst();
    }

    @Transactional
    public CourseView restore(UUID id) {
        Course course = find(id);
        if (course.isArchived()) {
            course.restore();
            courses.flush();
            audit.record(AuditEntry.of("CourseRestored", "Course", id).withAfter(Map.of("title", course.getTitle())));
        }
        return views(List.of(course)).getFirst();
    }

    /** Active courses that teach the skill to at least the level, by title. */
    @Override
    public List<CourseRef> teaching(UUID skillId, int minimumLevel) {
        List<UUID> ids = taughtSkills.coursesTeaching(skillId, minimumLevel);
        return courses.findAllById(ids).stream().filter(c -> !c.isArchived())
                .sorted(Comparator.comparing(Course::getTitle, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new CourseRef(c.getId(), c.getTitle())).toList();
    }

    Course resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return courses.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    Map<UUID, Course> byIds(Collection<UUID> ids) {
        return courses.findAllById(ids).stream().collect(Collectors.toMap(Course::getId, Function.identity()));
    }

    /** Skill id → the level the course teaches, in insertion order. */
    Map<UUID, Integer> taught(UUID courseId) {
        Map<UUID, Integer> levels = new LinkedHashMap<>();
        taughtSkills.findByCourseId(courseId).forEach(s -> levels.put(s.getSkillId(), s.getLevel()));
        return levels;
    }

    private record Draft(String title, String description, BigDecimal durationHours, String link,
            Map<UUID, Integer> taught) {}

    /** Body 400s first (title, description, duration, link, skills, unknown skills), then archived skills (409). */
    private Draft validate(CourseCommand c, Course current) {
        String title = HrRecords.collapse(Text.required(c.title(), 200, "title"));
        String description = Text.optional(c.description(), 5000, "description");
        BigDecimal duration = c.durationHours();
        if (duration != null) {
            // Magnitude first, so a huge exponent is never expanded by setScale.
            if (duration.signum() <= 0 || duration.compareTo(MAX_BEFORE_ROUNDING) >= 0) {
                throw ApiProblem.badRequestField("durationHours", DURATION);
            }
            duration = duration.setScale(1, RoundingMode.HALF_UP);
            if (duration.signum() <= 0) {
                throw ApiProblem.badRequestField("durationHours", DURATION);
            }
        }
        String link = Text.optional(c.link(), 500, "link");
        if (link != null) {
            String lower = link.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw ApiProblem.badRequestField("link", LINK);
            }
            // the DB check is case-sensitive (^https?://): store the scheme in lower case
            int colon = link.indexOf(':');
            link = lower.substring(0, colon) + link.substring(colon);
        }
        Map<UUID, Integer> taught = new LinkedHashMap<>();
        List<TaughtSkill> raw = c.skills() == null ? List.of() : c.skills();
        for (int i = 0; i < raw.size(); i++) {
            TaughtSkill t = raw.get(i);
            if (t == null || t.skillId() == null) {
                throw ApiProblem.badRequestField("skills[" + i + "].skillId", SkillService.UNKNOWN);
            }
            if (t.level() == null || t.level() < 1 || t.level() > 5) {
                throw ApiProblem.badRequestField("skills[" + i + "].level", LEVEL);
            }
            if (taught.containsKey(t.skillId())) {
                throw ApiProblem.badRequestField("skills[" + i + "].skillId", REPEATED);
            }
            taught.put(t.skillId(), t.level());
        }
        List<UUID> skillIds = new ArrayList<>(taught.keySet());
        Map<UUID, Skill> found = skills.byIds(skillIds);
        for (int i = 0; i < skillIds.size(); i++) {
            if (!found.containsKey(skillIds.get(i))) {
                throw ApiProblem.badRequestField("skills[" + i + "].skillId", SkillService.UNKNOWN);
            }
        }
        Set<UUID> kept = current == null ? Set.of() : new HashSet<>(taught(current.getId()).keySet());
        for (UUID skillId : skillIds) {
            if (found.get(skillId).isArchived() && !kept.contains(skillId)) {
                HrRecords.requireNotArchived(true);
            }
        }
        return new Draft(title, description, duration, link, taught);
    }

    private void replaceSkills(UUID courseId, Map<UUID, Integer> taught) {
        taughtSkills.deleteByCourse(courseId);
        taughtSkills.flush();
        taught.forEach((skillId, level) -> taughtSkills.save(new CourseSkill(Ids.newId(), courseId, skillId, level)));
        taughtSkills.flush();
    }

    private List<CourseView> views(List<Course> list) {
        Map<UUID, List<CourseSkill>> byCourse = taughtSkills.findByCourseIdIn(list.stream().map(Course::getId).toList())
                .stream().collect(Collectors.groupingBy(CourseSkill::getCourseId));
        Map<UUID, Skill> skillNames = skills.byIds(byCourse.values().stream().flatMap(List::stream)
                .map(CourseSkill::getSkillId).collect(Collectors.toSet()));
        return list.stream().map(c -> new CourseView(c.getId(), c.getTitle(), c.getDescription(), c.getDurationHours(),
                c.getLink(),
                byCourse.getOrDefault(c.getId(), List.of()).stream()
                        .map(s -> new TaughtSkillView(s.getSkillId(), skillNames.get(s.getSkillId()).getName(),
                                s.getLevel()))
                        .sorted(Comparator.comparing(TaughtSkillView::name, String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                c.getArchivedAt(), c.getVersion())).toList();
    }

    private Course find(UUID id) {
        TenantContext.requireTenantId();
        return courses.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private static Map<String, Object> snapshot(Course c, Map<UUID, Integer> taught) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", c.getTitle());
        values.put("description", c.getDescription());
        values.put("durationHours", c.getDurationHours() == null ? null : c.getDurationHours().toPlainString());
        values.put("link", c.getLink());
        values.put("skills", taught.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList());
        return values;
    }
}
