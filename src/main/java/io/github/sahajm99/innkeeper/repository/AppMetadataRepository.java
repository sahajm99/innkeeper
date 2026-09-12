package io.github.sahajm99.innkeeper.repository;

import io.github.sahajm99.innkeeper.model.AppMetadata;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppMetadataRepository extends JpaRepository<AppMetadata, String> {
}
