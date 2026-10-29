package com.kmg.eventplatform.user.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Is the email taken by another user? */
    boolean existsByEmailAndIdNot(String email, UUID id);
}
