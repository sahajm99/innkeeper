package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.Employee;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The manager's staff list: hiring, and letting somebody go without losing what they did. */
class EmployeeServiceTest extends AbstractServiceTest {

    private static final LocalDate HIRED_ON = LocalDate.of(2024, 6, 15);

    @Autowired EmployeeService employees;

    private Long branchId;

    @BeforeEach
    void createTheBranch() {
        branchId = inTransaction(data -> data.branch("DEN").getId());
    }

    @Test
    void anEmployeeIsAddedActiveAndCanBeDeactivatedLater() {
        Employee hired = employees.add(branchId, "Ben", "Sample", "BEN.Sample@example.com",
            "Front desk", HIRED_ON);

        assertThat(hired.getId()).isNotNull();
        assertThat(hired.isActive()).isTrue();
        assertThat(hired.getEmail()).as("stored the way it is looked up")
            .isEqualTo("ben.sample@example.com");
        assertThat(hired.getBranch().getCode()).isEqualTo("DEN");
        assertThat(hired.getHiredOn()).isEqualTo(HIRED_ON);
        assertThat(employees.list()).extracting(Employee::getId).containsExactly(hired.getId());

        Employee gone = employees.deactivate(hired.getId());

        assertThat(gone.isActive()).isFalse();
        assertThat(jdbc.queryForObject("select active from employee where id = ?", Boolean.class,
            hired.getId())).isFalse();
        assertThat(employees.list())
            .as("the row stays, so their fines and payments still point somewhere")
            .hasSize(1);
    }

    @Test
    void twoEmployeesCannotShareAnEmailAddress() {
        employees.add(branchId, "Ben", "Sample", "ben.sample@example.com", "Front desk", HIRED_ON);

        assertThatThrownBy(() -> employees.add(branchId, "Benjamin", "Sample",
            "ben.sample@example.com", "Night auditor", HIRED_ON))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count("employee")).isEqualTo(1);
    }

    @Test
    void hiringIntoABranchThatIsNotThereIsNotFound() {
        assertThatThrownBy(() -> employees.add(-1L, "Ben", "Sample", "ben.sample@example.com",
            "Front desk", HIRED_ON))
            .isInstanceOf(NotFoundException.class);

        assertThat(count("employee")).isZero();
    }

    @Test
    void deactivatingSomebodyWhoIsNotThereIsNotFound() {
        assertThatThrownBy(() -> employees.deactivate(-1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theListIsOrderedByBranchThenSurname() {
        Long other = inTransaction(data -> data.branch("AUS").getId());
        employees.add(branchId, "Ben", "Sample", "ben.sample@example.com", "Front desk", HIRED_ON);
        employees.add(branchId, "Ada", "Example", "ada.example@example.com", "Manager", HIRED_ON);
        employees.add(other, "Eve", "Specimen", "eve.specimen@example.com", "Front desk", HIRED_ON);

        assertThat(employees.list()).extracting(Employee::getLastName)
            .containsExactly("Specimen", "Example", "Sample");
        assertThat(employees.list().get(0).getBranch().getCode())
            .as("the branch comes back with the employee").isEqualTo("AUS");
    }
}
