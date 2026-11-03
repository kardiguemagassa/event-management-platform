package com.kmg.eventplatform.event.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Composable criteria of the event catalog. Each one is a single predicate; the application layer combines them,
 * so a new filter is a new method here, not a new repository method per combination.
 */
public final class EventSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private EventSpecifications() {
    }

    /** Every event except the drafts of other organizers; no draft at all for an anonymous viewer. */
    public static Specification<Event> visibleTo(UUID viewer) {
        return (root, query, cb) -> viewer == null
                ? cb.notEqual(root.get("status"), EventStatus.DRAFT)
                : cb.or(cb.notEqual(root.get("status"), EventStatus.DRAFT), cb.equal(root.get("organizerId"), viewer));
    }

    /** Every event of the organizer, whatever its status. */
    public static Specification<Event> organizedBy(UUID organizer) {
        return (root, query, cb) -> cb.equal(root.get("organizerId"), organizer);
    }

    public static Specification<Event> hasStatus(EventStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Title or location containing the text, case-insensitive; LIKE wildcards in the text match literally. */
    public static Specification<Event> titleOrLocationContains(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.<String>get("title")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.<String>get("location")), pattern, LIKE_ESCAPE));
    }

    public static Specification<Event> startsAtOrAfter(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.<Instant>get("startAt"), from);
    }

    public static Specification<Event> idIn(Collection<UUID> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
