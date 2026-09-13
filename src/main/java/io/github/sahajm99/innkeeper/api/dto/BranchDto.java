package io.github.sahajm99.innkeeper.api.dto;

import java.math.BigDecimal;

import io.github.sahajm99.innkeeper.model.Branch;

/**
 * One hotel, as the branch cards and the API present it.
 *
 * <p>{@code fromRate} is the cheapest room the branch has, which is what a rate card says and what
 * the home page prints under each branch.</p>
 */
public record BranchDto(Long id, String code, String name, String tagline, String city,
    String state, BigDecimal fromRate, String timezone) {

    public static BranchDto of(Branch branch, BigDecimal fromRate) {
        return new BranchDto(branch.getId(), branch.getCode(), branch.getName(),
            branch.getTagline(), branch.getCity(), branch.getState(), fromRate,
            branch.getTimezone());
    }
}
