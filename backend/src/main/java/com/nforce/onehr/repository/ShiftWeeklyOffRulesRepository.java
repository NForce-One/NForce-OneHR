package com.nforce.onehr.repository;

import com.nforce.onehr.entity.ShiftWeeklyOffRules;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShiftWeeklyOffRulesRepository extends JpaRepository<ShiftWeeklyOffRules, UUID> {

    // The table has exactly one row, enforced at the DB level (see V158) — this is the one and
    // only way any consumer ever reads it.
    Optional<ShiftWeeklyOffRules> findBySingletonTrue();
}
