package com.nforce.onehr.repository;

import com.nforce.onehr.entity.AttendanceRules;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRulesRepository extends JpaRepository<AttendanceRules, UUID> {

    // The table has exactly one row, enforced at the DB level (see V162) — this is the one and
    // only way any consumer ever reads it.
    Optional<AttendanceRules> findBySingletonTrue();
}
