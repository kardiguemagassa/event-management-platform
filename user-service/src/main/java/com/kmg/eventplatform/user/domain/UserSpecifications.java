package com.kmg.eventplatform.user.domain;

import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Composable criteria of the profile search (administration). */
public final class UserSpecifications {

    private static final char LIKE_ESCAPE = '\\';

    private UserSpecifications() {
    }

    /** Name or e-mail containing the text, case-insensitive; LIKE wildcards in the text match literally. */
    public static Specification<User> nameOrEmailContains(String text) {
        String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.<String>get("fullName")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.<String>get("email")), pattern, LIKE_ESCAPE));
    }

    public static Specification<User> idIn(Collection<UUID> ids) {
        return (root, query, cb) -> root.get("id").in(ids);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
