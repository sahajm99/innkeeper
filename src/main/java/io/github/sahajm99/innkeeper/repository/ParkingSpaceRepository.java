package io.github.sahajm99.innkeeper.repository;

import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.ParkingKind;
import io.github.sahajm99.innkeeper.model.ParkingSpace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, Long> {

    List<ParkingSpace> findByBranchIdAndBookingIsNullAndKindOrderBySpaceNumber(Long branchId, ParkingKind kind);

    Optional<ParkingSpace> findByBookingId(Long bookingId);

    /**
     * Claims a free space. The WHERE clause makes this a conditional update, so two check-ins
     * racing for the last space cannot both win: the loser gets 0 rows back.
     */
    @Modifying
    @Query("update ParkingSpace p set p.booking.id = :bookingId where p.id = :spaceId and p.booking is null")
    int assign(@Param("spaceId") Long spaceId, @Param("bookingId") Long bookingId);

    @Modifying
    @Query("update ParkingSpace p set p.booking = null where p.booking.id = :bookingId")
    int release(@Param("bookingId") Long bookingId);
}
