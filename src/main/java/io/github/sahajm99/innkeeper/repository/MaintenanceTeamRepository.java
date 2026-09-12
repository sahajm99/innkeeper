package io.github.sahajm99.innkeeper.repository;

import java.util.List;

import io.github.sahajm99.innkeeper.model.MaintenanceTeam;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaintenanceTeamRepository extends JpaRepository<MaintenanceTeam, Long> {

    List<MaintenanceTeam> findByBranchIdOrderByName(Long branchId);
}
