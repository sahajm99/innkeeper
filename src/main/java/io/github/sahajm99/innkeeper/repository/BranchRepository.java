package io.github.sahajm99.innkeeper.repository;

import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.Branch;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByCode(String code);

    List<Branch> findAllByOrderByName();
}
