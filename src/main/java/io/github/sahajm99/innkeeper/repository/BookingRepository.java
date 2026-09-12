package io.github.sahajm99.innkeeper.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import io.github.sahajm99.innkeeper.domain.BookingStatus;
import io.github.sahajm99.innkeeper.model.Booking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByConfirmationCode(String code);

    /**
     * Whether the room is still held by a stay that has not checked out on or after the day it
     * agreed to leave. Such an overstay has no room nights left, so nothing but this keeps its room
     * off the market for a stay that would start today.
     */
    boolean existsByRoomIdAndStatusAndCheckOutDateLessThanEqual(Long roomId, BookingStatus status,
        LocalDate date);

    /** Every stay booked with this email address, newest first. Email is matched case-insensitively. */
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest "
        + "where lower(b.guest.email) = lower(:email) order by b.checkInDate desc")
    List<Booking> findByGuestEmail(@Param("email") String email);

    /** Bookings due to check in on the date; pass a null branch for every branch. */
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest "
        + "where b.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CONFIRMED "
        + "and b.checkInDate = :date and (:branchId is null or r.branch.id = :branchId) "
        + "order by r.branch.name, r.roomNumber")
    List<Booking> arrivals(@Param("branchId") Long branchId, @Param("date") LocalDate date);

    /** In-house bookings due out on or before the date, so overstays keep showing up. */
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest "
        + "where b.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CHECKED_IN "
        + "and b.checkOutDate <= :date and (:branchId is null or r.branch.id = :branchId) "
        + "order by b.checkOutDate, r.roomNumber")
    List<Booking> departures(@Param("branchId") Long branchId, @Param("date") LocalDate date);

    @Query("select count(b) from Booking b "
        + "where b.status = io.github.sahajm99.innkeeper.domain.BookingStatus.CHECKED_IN "
        + "and b.room.branch.id = :branchId")
    long occupiedRooms(@Param("branchId") Long branchId);

    /** The staff desk search: confirmation code, guest email or guest name, newest stay first. */
    @Query("select b from Booking b join fetch b.room r join fetch r.branch join fetch b.guest g "
        + "where lower(b.confirmationCode) like lower(concat('%', :q, '%')) "
        + "or lower(g.email) like lower(concat('%', :q, '%')) "
        + "or lower(concat(g.firstName, ' ', g.lastName)) like lower(concat('%', :q, '%')) "
        + "order by b.checkInDate desc")
    List<Booking> search(@Param("q") String q);

    List<Booking> findByCheckInDateOrderByCheckInDate(LocalDate date);
}
