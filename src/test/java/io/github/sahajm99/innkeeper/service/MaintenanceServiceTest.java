package io.github.sahajm99.innkeeper.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.domain.NotFoundException;
import io.github.sahajm99.innkeeper.model.Branch;
import io.github.sahajm99.innkeeper.model.MaintenanceRequest;
import io.github.sahajm99.innkeeper.model.MaintenanceStatus;
import io.github.sahajm99.innkeeper.model.MaintenanceTeam;
import io.github.sahajm99.innkeeper.model.Priority;
import io.github.sahajm99.innkeeper.model.Room;
import io.github.sahajm99.innkeeper.model.RoomStatus;
import io.github.sahajm99.innkeeper.model.RoomType;
import io.github.sahajm99.innkeeper.service.MaintenanceService.Board;
import io.github.sahajm99.innkeeper.support.AbstractServiceTest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The maintenance board, and the one rule that connects it to the rest of the inn: a room only
 * goes out of service when nobody is booked into it from today on, and it only comes back when
 * the last job holding it is finished.
 */
class MaintenanceServiceTest extends AbstractServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);
    private static final String DESCRIPTION = "Water drips from the bathroom faucet.";

    @Autowired MaintenanceService maintenance;

    private Long branchId;
    private Long bookedRoomId;
    private Long freeRoomId;

    @BeforeEach
    void createTheBranchAndItsRooms() {
        inTransaction(data -> {
            Branch denton = data.branch("DEN");
            RoomType standard = data.roomType("STANDARD", 2);
            Room booked = data.room(denton, standard, "106", "89.00");
            Room free = data.room(denton, standard, "107", "89.00");
            branchId = denton.getId();
            bookedRoomId = booked.getId();
            freeRoomId = free.getId();
            return null;
        });
    }

    // --- taking a room out of service -------------------------------------------------------

    @Test
    void aRoomWithUpcomingStaysCannotBeTakenOutOfService() {
        inTransaction(data -> data.booking(entityManager.find(Room.class, bookedRoomId),
            data.guest("ada@example.com"), TODAY.plusDays(2), TODAY.plusDays(4),
            BookingStatus.CONFIRMED));

        assertThatThrownBy(() -> maintenance.create(branchId, bookedRoomId,
            "Leaking bathroom faucet", DESCRIPTION, Priority.URGENT, "Ben Sample", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Room 106 has upcoming stays");

        assertThat(statusOf(bookedRoomId)).isEqualTo("AVAILABLE");
        assertThat(count("maintenance_request")).isZero();
    }

    @Test
    void aRoomWhoseStaysAreAllInThePastGoesOutOfServiceWithTheJob() {
        inTransaction(data -> data.booking(entityManager.find(Room.class, freeRoomId),
            data.guest("ada@example.com"), TODAY.minusDays(3), TODAY.minusDays(1),
            BookingStatus.CHECKED_OUT));

        MaintenanceRequest request = maintenance.create(branchId, freeRoomId,
            "Leaking bathroom faucet", DESCRIPTION, Priority.URGENT, "Ben Sample", true);

        assertThat(request.getStatus()).isEqualTo(MaintenanceStatus.OPEN);
        assertThat(request.getCreatedAt()).isEqualTo(clock.instant());
        assertThat(request.getRoom().getRoomNumber()).isEqualTo("107");
        assertThat(statusOf(freeRoomId)).isEqualTo("OUT_OF_SERVICE");
    }

    @Test
    void aJobWithNoRoomIsFineAndTakesNothingOutOfService() {
        MaintenanceRequest request = maintenance.create(branchId, null, "Lobby lamp flickers",
            DESCRIPTION, Priority.LOW, "Cleo Placeholder", false);

        assertThat(request.getRoom()).isNull();
        assertThat(request.getPriority()).isEqualTo(Priority.LOW);
        assertThat(statusOf(freeRoomId)).isEqualTo("AVAILABLE");
    }

    @Test
    void anUnknownBranchIsNotFound() {
        assertThatThrownBy(() -> maintenance.create(-1L, null, "Lobby lamp flickers", DESCRIPTION,
            Priority.LOW, "Cleo Placeholder", false))
            .isInstanceOf(NotFoundException.class);
    }

    // --- working the board ------------------------------------------------------------------

    @Test
    void finishingTheLastJobHoldingARoomPutsItBackInService() {
        MaintenanceRequest request = maintenance.create(branchId, freeRoomId,
            "Leaking bathroom faucet", DESCRIPTION, Priority.URGENT, "Ben Sample", true);

        MaintenanceRequest started = maintenance.start(request.getId());
        assertThat(started.getStatus()).isEqualTo(MaintenanceStatus.IN_PROGRESS);
        assertThat(started.getStartedAt()).isEqualTo(clock.instant());

        MaintenanceRequest finished = maintenance.done(request.getId());

        assertThat(finished.getStatus()).isEqualTo(MaintenanceStatus.DONE);
        assertThat(finished.getCompletedAt()).isEqualTo(clock.instant());
        assertThat(statusOf(freeRoomId)).isEqualTo("AVAILABLE");
    }

    @Test
    void theRoomStaysOutOfServiceWhileAnotherJobIsStillOpenForIt() {
        MaintenanceRequest first = maintenance.create(branchId, freeRoomId, "Leaking faucet",
            DESCRIPTION, Priority.URGENT, "Ben Sample", true);
        MaintenanceRequest second = maintenance.create(branchId, freeRoomId, "Cracked window",
            DESCRIPTION, Priority.NORMAL, "Ada Example", true);

        maintenance.done(first.getId());
        assertThat(statusOf(freeRoomId)).as("the second job still holds it")
            .isEqualTo("OUT_OF_SERVICE");

        maintenance.done(second.getId());
        assertThat(statusOf(freeRoomId)).isEqualTo("AVAILABLE");
    }

    @Test
    void aJobCanBeGivenToATeam() {
        MaintenanceRequest request = maintenance.create(branchId, freeRoomId, "Leaking faucet",
            DESCRIPTION, Priority.URGENT, "Ben Sample", false);
        Long teamId = inTransaction(data -> data.maintenanceTeam(
            entityManager.find(Branch.class, branchId), "Engineering").getId());

        MaintenanceRequest assigned = maintenance.assignTeam(request.getId(), teamId);

        assertThat(assigned.getTeam()).extracting(MaintenanceTeam::getName)
            .isEqualTo("Engineering");
        assertThat(jdbc.queryForObject("select team_id from maintenance_request where id = ?",
            Long.class, request.getId())).isEqualTo(teamId);
    }

    @Test
    void aRoomCanBePutBackInServiceOnItsOwn() {
        maintenance.create(branchId, freeRoomId, "Leaking faucet", DESCRIPTION, Priority.URGENT,
            "Ben Sample", true);

        Room back = maintenance.returnToService(freeRoomId);

        assertThat(back.getStatus()).isEqualTo(RoomStatus.AVAILABLE);
        assertThat(statusOf(freeRoomId)).isEqualTo("AVAILABLE");
    }

    @Test
    void theBoardSplitsTheLanesAndOnlyKeepsAWeekOfFinishedWork() {
        Instant now = clock.instant();
        inTransaction(data -> {
            Branch denton = entityManager.find(Branch.class, branchId);
            Room room = entityManager.find(Room.class, bookedRoomId);
            data.maintenanceRequest(denton, room, "Leaking faucet", Priority.URGENT,
                MaintenanceStatus.OPEN, null);
            data.maintenanceRequest(denton, null, "Television remote missing", Priority.NORMAL,
                MaintenanceStatus.IN_PROGRESS, null);
            data.maintenanceRequest(denton, null, "Hallway carpet replaced", Priority.NORMAL,
                MaintenanceStatus.DONE, now.minusSeconds(2 * 86400));
            data.maintenanceRequest(denton, null, "Gutter cleared in spring", Priority.LOW,
                MaintenanceStatus.DONE, now.minusSeconds(30 * 86400));
            return null;
        });
        jdbc.update("update room set status = 'OUT_OF_SERVICE' where id = ?", bookedRoomId);

        Board board = maintenance.board(branchId);

        assertThat(board.open()).extracting(MaintenanceRequest::getTitle)
            .containsExactly("Leaking faucet");
        assertThat(board.inProgress()).extracting(MaintenanceRequest::getTitle)
            .containsExactly("Television remote missing");
        assertThat(board.done()).extracting(MaintenanceRequest::getTitle)
            .as("a job finished a month ago has left the board")
            .containsExactly("Hallway carpet replaced");
        assertThat(board.outOfService()).extracting(Room::getRoomNumber).containsExactly("106");
    }

    @Test
    void theBoardForEveryBranchCoversEveryBranch() {
        Long other = inTransaction(data -> data.branch("FTW").getId());
        maintenance.create(branchId, null, "Lobby lamp flickers", DESCRIPTION, Priority.LOW,
            "Ben Sample", false);
        maintenance.create(other, null, "Courtyard gate sticks", DESCRIPTION, Priority.NORMAL,
            "Cleo Placeholder", false);

        assertThat(maintenance.board(null).open()).hasSize(2);
        assertThat(maintenance.board(branchId).open()).hasSize(1);
    }

    private String statusOf(Long roomId) {
        return jdbc.queryForObject("select status from room where id = ?", String.class, roomId);
    }
}
