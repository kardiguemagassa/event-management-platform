package com.kmg.eventplatform.registration.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Port to the service owning user profiles: display names only, never personal contact details. */
public interface AttendeeDirectory {

    /**
     * @param userIds at most one page of attendees (100)
     * @return the display name of each user who has a profile; users without one are absent
     * @throws DownstreamUnavailableException if the answer cannot be obtained
     */
    Map<UUID, String> namesOf(Set<UUID> userIds);
}
