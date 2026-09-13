package io.github.sahajm99.innkeeper.api.dto;

import java.math.BigDecimal;

import io.github.sahajm99.innkeeper.model.Room;

/**
 * One room with its branch and its type flattened into it.
 *
 * <p>The branch is repeated on every room on purpose: a list of rooms is what a client renders, and
 * asking it to join three responses together to print "Denton Square 101" would be a worse API than
 * a few repeated strings.</p>
 */
public record RoomDto(Long id, Long branchId, String branchCode, String branchName,
    String roomNumber, int floor, RoomTypeDto type, BigDecimal nightlyRate, String status) {

    public static RoomDto of(Room room) {
        return new RoomDto(room.getId(), room.getBranch().getId(), room.getBranch().getCode(),
            room.getBranch().getName(), room.getRoomNumber(), room.getFloor(),
            RoomTypeDto.of(room.getRoomType()), room.getNightlyRate(), room.getStatus().name());
    }
}
