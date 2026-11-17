package com.fortivance.vendor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "vendors")
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    /** Null for direct vendors; set for subcontractors (fourth parties). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_vendor_id")
    @JsonIgnore
    private Vendor parent;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "service_category", nullable = false, length = 80)
    private String serviceCategory;

    @Column(name = "service_description", length = 500)
    private String serviceDescription;

    @Column(name = "risk_tier", nullable = false, length = 20)
    private String riskTier;

    @Column(name = "is_material", nullable = false)
    private boolean material;

    @Column(nullable = false, length = 60)
    private String country;

    @Column(name = "data_access", nullable = false, length = 30)
    private String dataAccess;

    @Column(name = "contract_start")
    private LocalDate contractStart;

    @Column(name = "contract_end")
    private LocalDate contractEnd;

    @Column(name = "last_assessed_on")
    private LocalDate lastAssessedOn;

    @Column(name = "next_review_due")
    private LocalDate nextReviewDue;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Vendor() { }

    public Long getId() { return id; }
    public Long getBankId() { return bankId; }
    public Vendor getParent() { return parent; }
    public Long getParentVendorId() { return parent == null ? null : parent.getId(); }
    public boolean isFourthParty() { return parent != null; }
    public String getName() { return name; }
    public String getServiceCategory() { return serviceCategory; }
    public String getServiceDescription() { return serviceDescription; }
    public String getRiskTier() { return riskTier; }
    public boolean isMaterial() { return material; }
    public String getCountry() { return country; }
    public String getDataAccess() { return dataAccess; }
    public LocalDate getContractStart() { return contractStart; }
    public LocalDate getContractEnd() { return contractEnd; }
    public LocalDate getLastAssessedOn() { return lastAssessedOn; }
    public LocalDate getNextReviewDue() { return nextReviewDue; }
    public boolean isReviewOverdue() { return nextReviewDue != null && nextReviewDue.isBefore(LocalDate.now()); }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
