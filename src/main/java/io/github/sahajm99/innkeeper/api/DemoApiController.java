package io.github.sahajm99.innkeeper.api;

import io.github.sahajm99.innkeeper.api.dto.RaceResult;
import io.github.sahajm99.innkeeper.service.RaceDemoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The button on the home page: ten simultaneous bookings for one room on one night.
 *
 * <p>A POST because it writes - it creates a booking and cancels it again - and rate limited for
 * the same reason.</p>
 */
@RestController
@RequestMapping("/api/demo")
@Tag(name = "Demo", description = "The concurrency demonstration")
public class DemoApiController {

    private final RaceDemoService race;

    public DemoApiController(RaceDemoService race) {
        this.race = race;
    }

    @PostMapping("/race")
    @Operation(summary = "Ten concurrent bookings for one room on one night; one wins")
    public RaceResult race() {
        return race.run();
    }
}
