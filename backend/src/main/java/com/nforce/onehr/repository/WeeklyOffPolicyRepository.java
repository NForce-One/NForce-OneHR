package com.nforce.onehr.repository;

import com.nforce.onehr.entity.WeeklyOffPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WeeklyOffPolicyRepository extends JpaRepository<WeeklyOffPolicy, UUID> {

    Optional<WeeklyOffPolicy> findByName(String name);

    boolean existsByNameIgnoreCase(String name);
}
