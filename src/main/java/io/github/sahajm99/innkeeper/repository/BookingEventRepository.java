package io.github.sahajm99.innkeeper.repository;

import java.util.List;

import io.github.sahajm99.innkeeper.model.BookingEvent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingEventRepository extends JpaRepository<BookingEvent, Long> {

    List<BookingEvent> findByBookingIdOrderByOccurredAt(Long bookingId);
}
