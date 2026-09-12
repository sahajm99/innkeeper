package io.github.sahajm99.innkeeper.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.model.Room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomRepository extends JpaRepository<Room, Long> {

    /**
     * In-service rooms that sleep the party, hold no night in [checkIn, checkOut) and, when the
     * stay would start today, are not still held by a guest who has overstayed. Every filter
     * except the guest count is optional: pass null to leave it out.
     */
    @Query("""
        select r from Room r join fetch r.roomType t join fetch r.branch b
        where r.status = io.github.sahajm99.innkeeper.model.RoomStatus.AVAILABLE
          and (:branchId is null or b.id = :branchId)
          and (:typeCode is null or t.code = :typeCode)
          and (:maxRate is null or r.nightlyRate <= :maxRate)
          and t.maxOccupancy >= :guests
          and not exists (select 1 from RoomNight n where n.room = r and n.nightDate >= :checkIn and n.nightDate < :checkOut)
          and (:checkIn > :today or not exists (select 1 from Booking o where o.room = r
               and o.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CHECKED_IN and o.checkOutDate <= :today))
        order by b.name, r.roomNumber""")
    List<Room> findAvailable(@Param("branchId") Long branchId,
                             @Param("typeCode") String typeCode,
                             @Param("maxRate") BigDecimal maxRate,
                             @Param("guests") int guests,
                             @Param("checkIn") LocalDate checkIn,
                             @Param("checkOut") LocalDate checkOut,
                             @Param("today") LocalDate today);

    List<Room> findByBranchIdOrderByRoomNumber(Long branchId);

    Optional<Room> findByBranchCodeAndRoomNumber(String branchCode, String roomNumber);
}
