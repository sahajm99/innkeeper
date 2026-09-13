package io.github.sahajm99.innkeeper.api.dto;

import io.github.sahajm99.innkeeper.model.RoomType;

/** What a room is: the name a guest reads and the two numbers that decide whether it fits. */
public record RoomTypeDto(String code, String name, int maxOccupancy, String bedSetup) {

    public static RoomTypeDto of(RoomType type) {
        return new RoomTypeDto(type.getCode(), type.getName(), type.getMaxOccupancy(),
            type.getBedSetup());
    }
}
