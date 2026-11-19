package com.fortivance.dependency;

import java.util.List;

/** Result of tracing an incident at one vendor up to the bank's business services. */
public record ImpactReport(
        VendorLink origin,
        List<VendorLink> vendorChain,
        List<AffectedApplication> applications,
        List<AffectedService> services,
        Summary summary) {

    /** depth 0 = vendor where the incident happened; depth 1 = the vendor that subcontracts to it, and so on. */
    public record VendorLink(long id, String name, String riskTier, boolean fourthParty, int depth, String path) { }

    public record AffectedApplication(
            long id, String code, String name, String criticality, Integer rtoHours,
            long viaVendorId, String viaVendorName, String dependencyType,
            boolean singlePointOfFailure, String alternative) { }

    public record AffectedService(
            long id, String code, String name, String criticality, boolean customerFacing,
            Integer impactToleranceHours, String bcpPlanRef, String businessOwner,
            String impactLevel,               // DISRUPTED if an essential app is hit, else DEGRADED
            List<String> viaApplications) { }

    public record Summary(
            int vendorsInChain, int applications, int services,
            int disruptedServices, int criticalServices, int customerFacingServices,
            Integer tightestImpactToleranceHours, int singlePointsOfFailure) { }
}
