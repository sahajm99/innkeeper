package io.github.sahajm99.innkeeper.web.form;

import io.github.sahajm99.innkeeper.model.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A job somebody at the desk is raising.
 *
 * <p>The room is optional because plenty of work is not in a room at all - a lobby lamp, a car
 * park light - but closing a room needs one, and the service refuses the pair rather than this
 * form: whether a room can be closed depends on the nights already sold, which only the database
 * knows.</p>
 */
public class MaintenanceForm {

    @NotNull(message = "Choose the hotel this is at")
    private Long branchId;

    private Long roomId;

    @NotBlank(message = "Say what is wrong in a few words")
    @Size(max = 120, message = "Keep the title to 120 characters")
    private String title;

    @NotBlank(message = "Describe what needs doing")
    @Size(max = 1000, message = "Keep it to 1000 characters")
    private String description;

    @NotNull(message = "Choose a priority")
    private Priority priority = Priority.NORMAL;

    /** Whether the room comes off the market until the job is done. */
    private boolean outOfService;

    public Long getBranchId() {
        return branchId;
    }

    public void setBranchId(Long branchId) {
        this.branchId = branchId;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? null : title.trim();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? null : description.trim();
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public boolean isOutOfService() {
        return outOfService;
    }

    public void setOutOfService(boolean outOfService) {
        this.outOfService = outOfService;
    }
}
