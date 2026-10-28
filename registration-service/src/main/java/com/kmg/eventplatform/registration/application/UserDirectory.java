package com.kmg.eventplatform.registration.application;

import java.util.UUID;

/**
 * Port to the service owning users.
 */
public interface UserDirectory {

    /**
     * @throws DownstreamUnavailableException if the answer cannot be obtained
     */
    boolean exists(UUID userId);
}
