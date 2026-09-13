package io.github.sahajm99.innkeeper.api;

import java.util.List;

import io.github.sahajm99.innkeeper.api.dto.BranchDto;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The three hotels. Small enough that there is nothing to page through and nothing to filter. */
@RestController
@RequestMapping("/api/branches")
@Tag(name = "Branches", description = "The hotels in the group")
public class BranchApiController {

    private final BranchRepository branches;
    private final RoomRepository rooms;

    public BranchApiController(BranchRepository branches, RoomRepository rooms) {
        this.branches = branches;
        this.rooms = rooms;
    }

    @GetMapping
    @Operation(summary = "Every branch, with the cheapest room it has")
    public List<BranchDto> branches() {
        return branches.findAllByOrderByName().stream()
            .map(branch -> BranchDto.of(branch, rooms.minimumRate(branch.getId())))
            .toList();
    }
}
