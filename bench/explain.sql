-- EXPLAIN ANALYZE of the three impact queries in dependency/ImpactService.java, on the benchmark database.
-- The app passes the vendor ids and application ids as IN lists; here they come from subqueries, which gives
-- the same row counts (the plan for the IN step may differ slightly). End-to-end API timing is measured separately.

SET @hot  = (SELECT id FROM vendors WHERE name = 'Fynzelo Talvex V00018 Pvt Ltd');  -- in 480 of 1,200 systems
SET @small = (SELECT id FROM vendors WHERE name LIKE '% V00001 Pvt Ltd');           -- an ordinary direct vendor
SET @sub  = (SELECT MIN(id) FROM vendors WHERE parent_vendor_id = @hot);            -- a subcontractor of the hotspot

SELECT 'register size' AS what,
       (SELECT COUNT(*) FROM vendors) AS vendors,
       (SELECT COUNT(*) FROM applications) AS systems,
       (SELECT COUNT(*) FROM business_services) AS services,
       (SELECT COUNT(*) FROM application_vendor_dependencies) AS system_vendor_edges,
       (SELECT COUNT(*) FROM service_application_dependencies) AS service_system_edges;
SELECT @hot AS hot_id, @small AS small_id, @sub AS sub_id;

-- Run each block for @sub, then @hot, then @small by changing @v.
SET @v = @sub;

-- 1. Vendor chain (recursive CTE)
EXPLAIN ANALYZE
WITH RECURSIVE chain (vendor_id, depth, path) AS (
    SELECT v.id, 0, CAST(v.name AS CHAR(1000)) FROM vendors v WHERE v.id = @v
    UNION ALL
    SELECT p.id, c.depth + 1, CONCAT(c.path, ' -> ', p.name)
    FROM chain c JOIN vendors v ON v.id = c.vendor_id JOIN vendors p ON p.id = v.parent_vendor_id
    WHERE c.depth < 10)
SELECT c.vendor_id, c.depth, c.path, v.name, v.risk_tier, (v.parent_vendor_id IS NOT NULL) AS fourth_party
FROM chain c JOIN vendors v ON v.id = c.vendor_id ORDER BY c.depth;

-- 2. Affected applications
EXPLAIN ANALYZE
WITH RECURSIVE chain (vendor_id, depth) AS (
    SELECT v.id, 0 FROM vendors v WHERE v.id = @v
    UNION ALL
    SELECT v.parent_vendor_id, c.depth + 1 FROM chain c JOIN vendors v ON v.id = c.vendor_id
    WHERE v.parent_vendor_id IS NOT NULL AND c.depth < 10)
SELECT a.id, a.code, a.name, a.criticality, a.rto_hours, v.id AS vendor_id, v.name AS vendor_name,
       avd.dependency_type, avd.is_single_point, avd.alternative_vendor
FROM application_vendor_dependencies avd
JOIN applications a ON a.id = avd.application_id
JOIN vendors v      ON v.id = avd.vendor_id
WHERE avd.vendor_id IN (SELECT vendor_id FROM chain)
ORDER BY FIELD(a.criticality, 'CRITICAL','HIGH','MEDIUM','LOW'), a.code;

-- 3. Affected business services
EXPLAIN ANALYZE
WITH RECURSIVE chain (vendor_id, depth) AS (
    SELECT v.id, 0 FROM vendors v WHERE v.id = @v
    UNION ALL
    SELECT v.parent_vendor_id, c.depth + 1 FROM chain c JOIN vendors v ON v.id = c.vendor_id
    WHERE v.parent_vendor_id IS NOT NULL AND c.depth < 10)
SELECT bs.id, bs.code, bs.name, bs.criticality, bs.is_customer_facing, bs.impact_tolerance_hours,
       bs.bcp_plan_ref, bs.business_owner, a.code AS app_code, sad.is_essential
FROM service_application_dependencies sad
JOIN business_services bs ON bs.id = sad.business_service_id
JOIN applications a       ON a.id  = sad.application_id
WHERE sad.application_id IN (SELECT avd.application_id FROM application_vendor_dependencies avd
                             WHERE avd.vendor_id IN (SELECT vendor_id FROM chain))
ORDER BY bs.impact_tolerance_hours, bs.code, a.code;
