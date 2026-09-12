package io.github.sahajm99.innkeeper.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import io.github.sahajm99.innkeeper.domain.BranchDates;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.MaintenanceRequest;
import io.github.sahajm99.innkeeper.model.MaintenanceStatus;
import io.github.sahajm99.innkeeper.model.MaintenanceTeam;
import io.github.sahajm99.innkeeper.model.Priority;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.repository.BranchRepository;
import io.github.sahajm99.innkeeper.repository.MaintenanceRequestRepository;
import io.github.sahajm99.innkeeper.repository.MaintenanceTeamRepository;
import io.github.sahajm99.innkeeper.repository.RoomNightRepository;
import io.github.sahajm99.innkeeper.repository.RoomRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The maintenance board, and the one thing that ties it to the rest of the inn: which rooms can be
 * sold.
 *
 * <p>A job may take its room out of service, but only when nobody is booked into it from today
 * on - the alternative is a guest arriving at a room that has quietly been closed. The room comes
 * back by itself when the last job holding it is finished, so a room is never left out of service
 * because somebody forgot to put it back.</p>
 */
@Service
public class MaintenanceService {

    /** How long finished work keeps showing on the board. */
    public static final int DONE_DAYS = 7;

    /** The work that is not finished, which is what keeps a room out of service. */
    private static final List<MaintenanceStatus> OUTSTANDING =
        List.of(MaintenanceStatus.OPEN, MaintenanceStatus.IN_PROGRESS);

    /** The three lanes of the board, plus the rooms that are closed while they are worked on. */
    public record Board(List<MaintenanceRequest> open, List<MaintenanceRequest> inProgress,
        List<MaintenanceRequest> done, List<Room> outOfService) {
    }

    private final MaintenanceRequestRepository requests;
    private final MaintenanceTeamRepository teams;
    private final BranchRepository branches;
    private final RoomRepository rooms;
    private final RoomNightRepository roomNights;
    private final Clock clock;

    public MaintenanceService(MaintenanceRequestRepository requests,
            MaintenanceTeamRepository teams, BranchRepository branches, RoomRepository rooms,
            RoomNightRepository roomNights, Clock clock) {
        this.requests = requests;
        this.teams = teams;
        this.branches = branches;
        this.rooms = rooms;
        this.roomNights = roomNights;
        this.clock = clock;
    }

    /**
     * Everything outstanding plus the last week of finished work, split into the three lanes the
     * board draws. A null branch is every branch.
     */
    @Transactional(readOnly = true)
    public Board board(Long branchId) {
        Instant doneSince = clock.instant().minus(DONE_DAYS, ChronoUnit.DAYS);
        List<MaintenanceRequest> all = requests.board(branchId, doneSince);
        return new Board(inStatus(all, MaintenanceStatus.OPEN),
            inStatus(all, MaintenanceStatus.IN_PROGRESS),
            inStatus(all, MaintenanceStatus.DONE),
            rooms.findByStatus(RoomStatus.OUT_OF_SERVICE, branchId));
    }

    /**
     * Raises a job, and closes its room when it was asked to.
     *
     * @throws IllegalStateException the room still has stays from today on
     * @throws IllegalArgumentException a room was to be closed but none was named
     * @throws NotFoundException there is no such branch or room
     */
    @Transactional
    public MaintenanceRequest create(Long branchId, Long roomId, String title, String description,
            Priority priority, String reportedBy, boolean takeRoomOutOfService) {
        Branch branch = branches.findById(branchId)
            .orElseThrow(() -> new NotFoundException("No branch with id " + branchId));
        Room room = roomId == null ? null : rooms.findById(roomId)
            .orElseThrow(() -> new NotFoundException("No room with id " + roomId));

        if (takeRoomOutOfService) {
            if (room == null) {
                throw new IllegalArgumentException("A room is needed to take one out of service");
            }
            LocalDate today = BranchDates.today(clock, branch.zone());
            if (roomNights.existsByRoomIdAndNightDateGreaterThanEqual(room.getId(), today)) {
                throw new IllegalStateException(
                    "Room " + room.getRoomNumber() + " has upcoming stays");
            }
            room.setStatus(RoomStatus.OUT_OF_SERVICE);
        }

        MaintenanceRequest request = new MaintenanceRequest();
        request.setBranch(branch);
        request.setRoom(room);
        request.setTitle(title);
        request.setDescription(description);
        request.setPriority(priority);
        request.setStatus(MaintenanceStatus.OPEN);
        request.setReportedBy(reportedBy);
        request.setCreatedAt(clock.instant());
        return requests.save(request);
    }

    /** Puts a job in progress. The first start is the one the board dates it by. */
    @Transactional
    public MaintenanceRequest start(Long id) {
        MaintenanceRequest request = require(id);
        if (request.getStatus() == MaintenanceStatus.DONE) {
            throw new IllegalStateException("That job is already finished");
        }
        request.setStatus(MaintenanceStatus.IN_PROGRESS);
        if (request.getStartedAt() == null) {
            request.setStartedAt(clock.instant());
        }
        return request;
    }

    /**
     * Finishes a job, and puts its room back on the market unless another unfinished job is still
     * holding it.
     */
    @Transactional
    public MaintenanceRequest done(Long id) {
        MaintenanceRequest request = require(id);
        request.setStatus(MaintenanceStatus.DONE);
        request.setCompletedAt(clock.instant());

        Room room = request.getRoom();
        if (room != null && room.getStatus() == RoomStatus.OUT_OF_SERVICE
                && !requests.existsByRoomIdAndIdNotAndStatusIn(room.getId(), id, OUTSTANDING)) {
            room.setStatus(RoomStatus.AVAILABLE);
        }
        return request;
    }

    @Transactional
    public MaintenanceRequest assignTeam(Long id, Long teamId) {
        MaintenanceRequest request = require(id);
        MaintenanceTeam team = teamId == null ? null : teams.findById(teamId)
            .orElseThrow(() -> new NotFoundException("No maintenance team with id " + teamId));
        request.setTeam(team);
        return request;
    }

    /** Puts a room back on the market by hand, whatever the board says. */
    @Transactional
    public Room returnToService(Long roomId) {
        Room room = rooms.findById(roomId)
            .orElseThrow(() -> new NotFoundException("No room with id " + roomId));
        room.setStatus(RoomStatus.AVAILABLE);
        return room;
    }

    private MaintenanceRequest require(Long id) {
        return requests.findById(id)
            .orElseThrow(() -> new NotFoundException("No maintenance request with id " + id));
    }

    private List<MaintenanceRequest> inStatus(List<MaintenanceRequest> all,
            MaintenanceStatus status) {
        return all.stream().filter(request -> request.getStatus() == status).toList();
    }
}
