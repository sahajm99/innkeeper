package io.github.sahajm99.innkeeper.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.MaintenanceRequest;
import io.github.sahajm99.innkeeper.model.MaintenanceTeam;
import io.github.sahajm99.innkeeper.model.Priority;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.MaintenanceTeamRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.github.sahajm99.innkeeper.service.MaintenanceService;
import io.github.sahajm99.innkeeper.service.MaintenanceService.Board;
import io.github.sahajm99.innkeeper.service.StaffDeskService;
import io.github.sahajm99.innkeeper.web.form.MaintenanceForm;
import jakarta.validation.Valid;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The maintenance board.
 *
 * <p>Three lanes, and every card carries the two buttons that move it, because a board you can
 * only read is a list. There is no dragging: a card moves when somebody presses Start or Done,
 * which works on a phone in a corridor and without any JavaScript at all.</p>
 */
@Controller
public class MaintenanceController {

    /** One card on the board, read once so the template never touches a lazy association. */
    public record Card(Long id, String title, String description, Long branchId, String branchName,
        Long roomId, String roomLabel, Priority priority, String teamName, String reportedBy,
        String age) {
    }

    /** One room in the "which room" select, labelled for the scope the board is showing. */
    public record RoomOption(Long id, String label) {
    }

    private final MaintenanceService maintenance;
    private final StaffDeskService desk;
    private final BranchRepository branches;
    private final RoomRepository rooms;
    private final MaintenanceTeamRepository teams;
    private final Clock clock;

    public MaintenanceController(MaintenanceService maintenance, StaffDeskService desk,
            BranchRepository branches, RoomRepository rooms, MaintenanceTeamRepository teams,
            Clock clock) {
        this.maintenance = maintenance;
        this.desk = desk;
        this.branches = branches;
        this.rooms = rooms;
        this.teams = teams;
        this.clock = clock;
    }

    @GetMapping("/staff/maintenance")
    public String board(@RequestParam(name = "branchId", required = false) String branchParam,
            @RequestParam(name = "roomId", required = false) Long roomId,
            @ModelAttribute("request") MaintenanceForm form, Authentication authentication,
            Model model) {
        Long branchId = scope(branchParam, authentication);
        if (form.getRoomId() == null && roomId != null) {
            prefill(form, roomId, branchId);
        }
        return render(branchId, form, model);
    }

    @PostMapping("/staff/maintenance")
    public String create(@RequestParam(name = "scope", required = false) String branchParam,
            @Valid @ModelAttribute("request") MaintenanceForm form, BindingResult binding,
            Authentication authentication, Model model, RedirectAttributes attributes) {
        Long branchId = scope(branchParam, authentication);
        if (binding.hasErrors()) {
            return render(branchId, form, model);
        }
        try {
            maintenance.create(form.getBranchId(), form.getRoomId(), form.getTitle(),
                form.getDescription(), form.getPriority(), actor(authentication),
                form.isOutOfService());
            Flash.message(attributes, "Request raised.");
        } catch (IllegalStateException | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        }
        return back(branchId);
    }

    @PostMapping("/staff/maintenance/{id}/start")
    public String start(@PathVariable("id") Long id,
            @RequestParam(name = "scope", required = false) String branchParam,
            Authentication authentication, RedirectAttributes attributes) {
        try {
            maintenance.start(id);
            Flash.message(attributes, "Job started.");
        } catch (IllegalStateException | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        }
        return back(scope(branchParam, authentication));
    }

    @PostMapping("/staff/maintenance/{id}/done")
    public String done(@PathVariable("id") Long id,
            @RequestParam(name = "scope", required = false) String branchParam,
            Authentication authentication, RedirectAttributes attributes) {
        try {
            maintenance.done(id);
            Flash.message(attributes, "Job finished.");
        } catch (IllegalStateException | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        }
        return back(scope(branchParam, authentication));
    }

    @PostMapping("/staff/maintenance/{id}/team")
    public String assignTeam(@PathVariable("id") Long id,
            @RequestParam(name = "teamId", required = false) Long teamId,
            @RequestParam(name = "scope", required = false) String branchParam,
            Authentication authentication, RedirectAttributes attributes) {
        try {
            maintenance.assignTeam(id, teamId);
            Flash.message(attributes, teamId == null ? "Team cleared." : "Team assigned.");
        } catch (IllegalStateException | IllegalArgumentException refused) {
            Flash.error(attributes, refused.getMessage());
        }
        return back(scope(branchParam, authentication));
    }

    @PostMapping("/staff/maintenance/rooms/{roomId}/return")
    public String returnToService(@PathVariable("roomId") Long roomId,
            @RequestParam(name = "scope", required = false) String branchParam,
            Authentication authentication, RedirectAttributes attributes) {
        Room room = maintenance.returnToService(roomId);
        Flash.message(attributes, "Room " + room.getRoomNumber() + " is back in service.");
        return back(scope(branchParam, authentication));
    }

    /** Everything the board draws, for one branch or for all of them. */
    private String render(Long branchId, MaintenanceForm form, Model model) {
        Board board = maintenance.board(branchId);
        List<Branch> inScope = inScope(branchId);
        if (form.getBranchId() == null) {
            form.setBranchId(branchId);
        }

        model.addAttribute("branchId", branchId);
        model.addAttribute("scope", scopeName(branchId));
        model.addAttribute("open", cards(board.open()));
        model.addAttribute("inProgress", cards(board.inProgress()));
        model.addAttribute("done", cards(board.done()));
        model.addAttribute("outOfService", roomRows(board.outOfService()));
        model.addAttribute("roomOptions", roomOptions(inScope, branchId == null));
        model.addAttribute("teamsByBranch", teamsByBranch(inScope));
        model.addAttribute("priorities", Priority.values());
        return "staff/maintenance";
    }

    private List<Card> cards(List<MaintenanceRequest> requests) {
        return requests.stream().map(request -> new Card(
            request.getId(),
            request.getTitle(),
            request.getDescription(),
            request.getBranch().getId(),
            request.getBranch().getName(),
            request.getRoom() == null ? null : request.getRoom().getId(),
            request.getRoom() == null ? null : "Room " + request.getRoom().getRoomNumber(),
            request.getPriority(),
            request.getTeam() == null ? null : request.getTeam().getName(),
            request.getReportedBy(),
            age(request.getCreatedAt()))).toList();
    }

    /** The rooms nobody can sell, with the branch they are at, for the return-to-service list. */
    private List<RoomOption> roomRows(List<Room> closed) {
        return closed.stream()
            .map(room -> new RoomOption(room.getId(),
                room.getBranch().getName() + ", room " + room.getRoomNumber()))
            .toList();
    }

    /**
     * The rooms the new-request form can name. With every branch on the board the label has to
     * carry the branch too, because room 101 exists three times.
     */
    private List<RoomOption> roomOptions(List<Branch> inScope, boolean allBranches) {
        List<RoomOption> options = new ArrayList<>();
        for (Branch branch : inScope) {
            for (Room room : rooms.findByBranchIdOrderByRoomNumber(branch.getId())) {
                options.add(new RoomOption(room.getId(), allBranches
                    ? branch.getName() + ", room " + room.getRoomNumber()
                    : "Room " + room.getRoomNumber()));
            }
        }
        return options;
    }

    /** A card only offers the teams of its own branch, which is the only place they work. */
    private Map<Long, List<MaintenanceTeam>> teamsByBranch(List<Branch> inScope) {
        Map<Long, List<MaintenanceTeam>> byBranch = new LinkedHashMap<>();
        for (Branch branch : inScope) {
            byBranch.put(branch.getId(), teams.findByBranchIdOrderByName(branch.getId()));
        }
        return byBranch;
    }

    /** "today", "yesterday", "4 days ago": how long a job has been waiting. */
    private String age(Instant created) {
        if (created == null) {
            return "";
        }
        LocalDate raised = created.atZone(clock.getZone()).toLocalDate();
        long days = ChronoUnit.DAYS.between(raised, LocalDate.now(clock));
        if (days <= 0) {
            return "today";
        }
        return days == 1 ? "yesterday" : days + " days ago";
    }

    /** The new-request form opened from a booking arrives with its room already chosen. */
    private void prefill(MaintenanceForm form, Long roomId, Long branchId) {
        rooms.findDetailed(roomId).ifPresent(room -> {
            form.setRoomId(room.getId());
            form.setBranchId(room.getBranch().getId());
        });
        if (form.getBranchId() == null) {
            form.setBranchId(branchId);
        }
    }

    private List<Branch> inScope(Long branchId) {
        if (branchId == null) {
            return branches.findAllByOrderByName();
        }
        return branches.findById(branchId).map(List::of).orElseGet(List::of);
    }

    private String scopeName(Long branchId) {
        if (branchId == null) {
            return StaffAdvice.ALL_BRANCHES;
        }
        return branches.findById(branchId).map(Branch::getName).orElse(StaffAdvice.ALL_BRANCHES);
    }

    private Long scope(String branchParam, Authentication authentication) {
        return StaffAdvice.branchScope(branchParam, desk.branchForUsername(actor(authentication)));
    }

    private String back(Long branchId) {
        return "redirect:/staff/maintenance" + StaffAdvice.carry(branchId);
    }

    private String actor(Authentication authentication) {
        return authentication == null ? "staff" : authentication.getName();
    }
}
