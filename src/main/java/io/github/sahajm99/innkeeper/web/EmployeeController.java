package io.github.sahajm99.innkeeper.web;

import java.time.LocalDate;
import java.util.List;

import io.github.sahajm99.innkeeper.model.Employee;
import io.github.sahajm99.innkeeper.service.EmployeeService;
import io.github.sahajm99.innkeeper.web.form.EmployeeForm;
import jakarta.validation.Valid;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Who works here.
 *
 * <p>Managers only, which the security chain decides; a clerk who types the address gets the
 * designed 403 rather than a page with the buttons removed. Nobody is ever deleted - an employee
 * who has left still signed the fines and payments they took - so the only ending is being marked
 * as no longer working here.</p>
 */
@Controller
public class EmployeeController {

    /** One row of the list, with the branch read while the session was still open. */
    public record Row(Long id, String name, String email, String position, String branchName,
        LocalDate hiredOn, boolean active) {
    }

    private final EmployeeService employees;

    public EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping("/staff/employees")
    public String list(@ModelAttribute("employee") EmployeeForm form, Model model) {
        return render(form, model);
    }

    @PostMapping("/staff/employees")
    public String add(@Valid @ModelAttribute("employee") EmployeeForm form, BindingResult binding,
            Model model, RedirectAttributes attributes) {
        if (!binding.hasErrors()) {
            try {
                Employee hired = employees.add(form.getBranchId(), form.getFirstName(),
                    form.getLastName(), form.getEmail(), form.getPosition(), form.getHiredOn());
                Flash.message(attributes,
                    hired.getFirstName() + " " + hired.getLastName() + " added.");
                return "redirect:/staff/employees";
            } catch (DataIntegrityViolationException duplicate) {
                binding.rejectValue("email", "duplicate",
                    "Somebody already works here with that email address");
            }
        }
        return render(form, model);
    }

    @PostMapping("/staff/employees/{id}/deactivate")
    public String deactivate(@PathVariable("id") Long id, RedirectAttributes attributes) {
        Employee left = employees.deactivate(id);
        Flash.message(attributes,
            left.getFirstName() + " " + left.getLastName() + " no longer works here.");
        return "redirect:/staff/employees";
    }

    private String render(EmployeeForm form, Model model) {
        List<Row> rows = employees.list().stream()
            .map(employee -> new Row(employee.getId(),
                employee.getFirstName() + " " + employee.getLastName(),
                employee.getEmail(), employee.getPosition(), employee.getBranch().getName(),
                employee.getHiredOn(), employee.isActive()))
            .toList();
        model.addAttribute("employees", rows);
        model.addAttribute("activeCount", rows.stream().filter(Row::active).count());
        return "staff/employees";
    }
}
