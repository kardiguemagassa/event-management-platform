package com.kmg.eventplatform.user.application;

import com.kmg.eventplatform.user.domain.User;

/** Outcome of saving a profile: {@code created} tells a first save (201) from an update (200). */
public record SavedProfile(User user, boolean created) {
}
