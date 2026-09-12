package io.github.sahajm99.innkeeper.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Key/value rows about the deployment itself, such as seeded_at and last_reset_at. */
@Entity
@Table(name = "app_metadata")
public class AppMetadata {

    /** Metadata key recording when the demo data was last seeded. */
    public static final String SEEDED_AT = "seeded_at";

    /** Metadata key recording when the demo data was last reset. */
    public static final String LAST_RESET_AT = "last_reset_at";

    @Id
    @Column(name = "meta_key", nullable = false, length = 40)
    private String metaKey;

    @Column(name = "meta_value", nullable = false, length = 200)
    private String metaValue;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public String getMetaKey() {
        return metaKey;
    }

    public void setMetaKey(String metaKey) {
        this.metaKey = metaKey;
    }

    public String getMetaValue() {
        return metaValue;
    }

    public void setMetaValue(String metaValue) {
        this.metaValue = metaValue;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
