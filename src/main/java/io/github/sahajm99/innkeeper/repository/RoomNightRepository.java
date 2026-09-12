package io.github.sahajm99.innkeeper.repository;

import java.time.LocalDate;
import java.util.List;

import io.github.sahajm99.innkeeper.model.RoomNight;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomNightRepository extends JpaRepository<RoomNight, Long> {

    /** The nights the room is already held for, over the half-open range [from, to). */
    @Query("select n.nightDate from RoomNight n where n.room.id = :roomId "
        + "and n.nightDate >= :from and n.nightDate < :to")
    List<LocalDate> bookedNights(@Param("roomId") Long roomId,
                                 @Param("from") LocalDate from,
                                 @Param("to") LocalDate to);

    /** Releases the nights from a date onwards, which is what an early check-out does. */
    long deleteByBookingIdAndNightDateGreaterThanEqual(Long bookingId, LocalDate from);

    /** Releases every night of a booking, which is what a cancellation does. */
    long deleteByBookingId(Long bookingId);
}
