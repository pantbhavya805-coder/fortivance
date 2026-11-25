# Future improvements

What would come next after the hackathon build, in rough order of value. Items marked "working today" on the About
page roadmap are not repeated here.

## 1. Model shared subcontractors (concentration risk)

**Today.** Each vendor has one `parent_vendor_id` (`V1__init_schema.sql`), so a subcontractor can sit under only one
vendor. The impact trace in `dependency/ImpactService.java` walks up that column with a recursive CTE.

**Why it matters.** In practice one data centre, cloud region or SMS aggregator often serves several of a bank's
vendors. That hidden shared dependency is concentration risk, and RBI's 2025 outsourcing Directions ask banks to map
dependencies on vendors and their key subcontractors. With a single parent column Fortivance cannot represent it, and an outage at the shared subcontractor
would be traced through only one of its customers.

**Change.**
- New migration (`V10__vendor_dependencies.sql`) adding an edge table, then backfilling it from the existing column:
  ```sql
  CREATE TABLE vendor_dependencies (
      vendor_id             BIGINT NOT NULL,   -- the vendor that relies on ...
      depends_on_vendor_id  BIGINT NOT NULL,   -- ... this subcontractor
      service_description   VARCHAR(255),
      disclosed_in_contract BOOLEAN NOT NULL DEFAULT FALSE,
      PRIMARY KEY (vendor_id, depends_on_vendor_id),
      CONSTRAINT fk_vd_vendor FOREIGN KEY (vendor_id) REFERENCES vendors(id),
      CONSTRAINT fk_vd_dep    FOREIGN KEY (depends_on_vendor_id) REFERENCES vendors(id),
      CONSTRAINT chk_vd_self  CHECK (vendor_id <> depends_on_vendor_id)
  );
  INSERT INTO vendor_dependencies (vendor_id, depends_on_vendor_id)
  SELECT parent_vendor_id, id FROM vendors WHERE parent_vendor_id IS NOT NULL;
  ```
- Rewrite the recursive CTE in `ImpactService` to walk the edge table. A graph with shared nodes can reach a vendor by
  two paths, so keep the visited path (as the query already builds) and skip a vendor already on it, and return each
  affected service once with the shortest path.
- Add a concentration-risk view: subcontractors ranked by how many direct vendors, systems and critical services
  sit above them. Show it on the dashboard and the vendor profile ("serves 3 of your vendors").
- Update the other readers of `parent_vendor_id`: `DependencyController` (graph edges), `EvidenceService`
  (subcontractors without assurance in the risk score), `IncidentService` and `VendorMatcher` (matching the affected
  party), `RegisterImportService` (the vendors template gets a "relies on" list instead of a single parent), and
  `Vendor.java`. Drop the old column in a later migration once nothing reads it.
- Tests: a diamond graph (two vendors sharing one subcontractor) must list each affected service once; a cycle in
  imported data must be rejected by the import and stopped by the depth limit.

## 2. Upload page for contracts and audit reports

**Today.** Evidence documents (vendor contracts and SOC 2 reports) are loaded by the seed migration
(`V2__seed_synthetic_data.sql`), which inserts a `documents` row pointing at a file under `sample-docs/`.
`PassageService` reads that file, splits it into passages, and the analysis works on any document in the table.
But there is no page for a bank to add its own contract or audit report: only incident notices and the register
CSVs can be uploaded. The About page roadmap calls "Load evidence" working today, which is true of the analysis
but not of loading.

**Where the documents come from in a real bank.** Contracts are signed between the bank and the vendor; SOC 2 and
similar reports are written by the vendor's independent auditor and shared with the bank under confidentiality.
Both are already in the bank's hands, so the risk team uploads them; nothing is collected from vendors directly.

**Change.**
- An Evidence > Upload document page (Approver or Analyst): choose the vendor, the document type (contract,
  SOC 2 Type I/II, ISO 27001 certificate, BCP test report), effective and expiry dates, and the file (PDF, Markdown
  or text, size-limited as incident uploads are).
- `POST /api/evidence/documents` (multipart): store the file outside the web root with a generated name, insert the
  `documents` row, extract the text with the existing `TextExtractor`, and split it with `PassageService`. Analysis
  then runs from the existing Analyse button, so Claude is called only when a person asks.
- Replacing a document (a new contract version or next year's SOC 2 report) keeps the old one, marks it superseded,
  and keeps its verified findings for the audit trail.
- Count uploads against the demo account limits, like incident uploads, so the shared AI budget stays protected.
- Treat uploaded text as untrusted, exactly as incident notices are.
- Until this ships, reword the About roadmap row to "Analysis working today; upload page planned".

## 3. Bank-specific settings screens

Impact tolerances and continuity plans are seeded in the database, and the risk-score weights are constants in
`EvidenceService.riskScore`. RBI expects all of these to be the bank's own choices, so they need admin screens with an
audit trail, and the weights need to move into a settings table.

## 4. Full RBI requirement library

The demo checks five requirements. Loading the remaining provisions of the outsourcing and cybersecurity Directions
is data, not code, but each needs gold labels before its AI results can be trusted.

## 5. Incident intake from the mailbox

Read vendor notices directly from the risk team's Titan mailbox (IMAP) instead of manual upload, keeping the same
sender-domain check and prompt-injection handling.

## 6. Single sign-on and integrations

- Sign in through the bank's identity provider (SAML or OIDC) instead of local passwords.
- Send approved actions to the bank's ticketing or GRC system.

## 7. Larger evaluation set

The evaluation covers 3 incidents, 1 injection test and 3 evidence documents. A pilot with a real bank's (redacted)
notices and contracts would give a meaningful accuracy figure and catch failure cases the synthetic set misses.

## 8. Smaller technical items

- **Flyway:** startup warns that MySQL 8.4 is newer than this Flyway version supports. Upgrade Flyway (with the
  `flyway-mysql` module) and re-run the migration tests.
- **Excel import:** accept `.xlsx` directly as well as CSV.
