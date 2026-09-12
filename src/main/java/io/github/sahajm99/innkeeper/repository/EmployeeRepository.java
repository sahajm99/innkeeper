package io.github.sahajm99.innkeeper.repository;

import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.Employee;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findAllByOrderByBranchNameAscLastNameAsc();

    Optional<Employee> findByEmail(String email);
}
