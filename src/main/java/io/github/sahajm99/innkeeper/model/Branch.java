package io.github.sahajm99.innkeeper.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A hotel. Owns its rooms, employees, teams, inventory, parking spaces and complaints. */
@Entity
@Table(name = "branch")
public class Branch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "tagline", nullable = false, length = 160)
    private String tagline;

    @Column(name = "description", nullable = false, length = 1000)
    private String description;

    @Column(name = "address_line", nullable = false, length = 200)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 80)
    private String city;

    @Column(name = "state", nullable = false, length = 40)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 16)
    private String postalCode;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "email", nullable = false, length = 160)
    private String email;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The zone "today" is measured in for this branch. */
    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
