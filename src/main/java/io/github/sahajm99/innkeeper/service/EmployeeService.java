package io.github.sahajm99.innkeeper.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Employee;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.EmployeeRepository;

import org.hibernate.Hibernate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staff list a manager keeps.
 *
 * <p>Nobody is ever deleted: fines and payments point at the employee who took them, and an audit
 * trail that loses its author is worth less than one with a name on it. Somebody who leaves is
 * marked inactive instead, and their row stays where the rest of the data can still reach it.</p>
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employees;
    private final BranchRepository branches;

    public EmployeeService(EmployeeRepository employees, BranchRepository branches) {
        this.employees = employees;
        this.branches = branches;
    }

    /** Everybody, by branch then surname, with the branch loaded for the page that lists them. */
    @Transactional(readOnly = true)
    public List<Employee> list() {
        List<Employee> staff = employees.findAllByOrderByBranchNameAscLastNameAsc();
        staff.forEach(employee -> Hibernate.initialize(employee.getBranch()));
        return staff;
    }

    /**
     * Hires somebody. The email is the natural key an account is later linked by, so it is stored
     * the way it is looked up.
     *
     * @throws NotFoundException there is no such branch
     * @throws org.springframework.dao.DataIntegrityViolationException somebody already has that
     *     email address
     */
    @Transactional
    public Employee add(Long branchId, String firstName, String lastName, String email,
            String position, LocalDate hiredOn) {
        Branch branch = branches.findById(branchId)
            .orElseThrow(() -> new NotFoundException("No branch with id " + branchId));

        Employee employee = new Employee();
        employee.setBranch(branch);
        employee.setFirstName(firstName);
        employee.setLastName(lastName);
        employee.setEmail(email == null ? null : email.trim().toLowerCase(Locale.ROOT));
        employee.setPosition(position);
        employee.setHiredOn(hiredOn);
        employee.setActive(true);
        // Flushed here so a duplicate email is refused by this call rather than at an unrelated
        // commit later on.
        return employees.saveAndFlush(employee);
    }

    /**
     * Marks somebody as no longer working here.
     *
     * @throws NotFoundException there is no such employee
     */
    @Transactional
    public Employee deactivate(Long id) {
        Employee employee = employees.findById(id)
            .orElseThrow(() -> new NotFoundException("No employee with id " + id));
        employee.setActive(false);
        Hibernate.initialize(employee.getBranch());
        return employee;
    }
}
