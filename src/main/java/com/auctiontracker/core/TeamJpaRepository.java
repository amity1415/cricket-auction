package com.auctiontracker.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data implementation of the {@link TeamRepository} port. */
public interface TeamJpaRepository extends TeamRepository, JpaRepository<Team, UUID> {
}
