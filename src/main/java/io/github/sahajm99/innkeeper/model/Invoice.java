package io.github.sahajm99.innkeeper.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * The bill for one booking. It snapshots the branch tax rate and stores its totals, so a reprint of
 * an old invoice never changes because a rate did.
 */
@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @Column(name = "room_subtotal", nullable = false, precision = 10, scale = 2)
    private BigDecimal roomSubtotal;

    @Column(name = "tax", nullable = false, precision = 10, scale = 2)
    private BigDecimal tax;

    @Column(name = "fines", nullable = false, precision = 10, scale = 2)
    private BigDecimal fines = new BigDecimal("0.00");

    @Column(name = "cancellation_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal cancellationFee = new BigDecimal("0.00");

    @Column(name = "total", nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private InvoiceStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineOrder")
    private List<InvoiceLine> lines = new ArrayList<>();

    /** Adds a line and owns both sides of the association. */
    public void addLine(InvoiceLine line) {
        line.setInvoice(this);
        lines.add(line);
    }

    /**
     * Rebuilds the invoice from a new set of lines, emptying the existing collection in place so
     * orphan removal deletes the old rows. Never replace the collection instance itself: Hibernate
     * rejects a reassigned collection that has orphan deletion turned on.
     */
    public void replaceLines(List<InvoiceLine> newLines) {
        lines.clear();
        newLines.forEach(this::addLine);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Booking getBooking() {
        return booking;
    }

    public void setBooking(Booking booking) {
        this.booking = booking;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public BigDecimal getRoomSubtotal() {
        return roomSubtotal;
    }

    public void setRoomSubtotal(BigDecimal roomSubtotal) {
        this.roomSubtotal = roomSubtotal;
    }

    public BigDecimal getTax() {
        return tax;
    }

    public void setTax(BigDecimal tax) {
        this.tax = tax;
    }

    public BigDecimal getFines() {
        return fines;
    }

    public void setFines(BigDecimal fines) {
        this.fines = fines;
    }

    public BigDecimal getCancellationFee() {
        return cancellationFee;
    }

    public void setCancellationFee(BigDecimal cancellationFee) {
        this.cancellationFee = cancellationFee;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }
}
