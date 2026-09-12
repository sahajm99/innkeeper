package io.github.sahajm99.innkeeper.model;

/** Invoice lifecycle; matches ck_invoice_status. */
public enum InvoiceStatus {
    OPEN,
    PAID,
    VOID
}
