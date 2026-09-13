package io.github.sahajm99.innkeeper.api.dto;

/** Who the booking is for. Phone numbers are collected but never handed back out. */
public record GuestDto(String firstName, String lastName, String email) {
}
