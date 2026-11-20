/*
 * Fortivance glossary: plain-language definitions of the banking terms and abbreviations used in the app.
 *
 * One list (TERMS) feeds three things:
 *   1. Inline help: the first time a term appears in each card it gets a dotted underline; hover, focus or
 *      tap shows the definition with a link to the guide. Works on text rendered later (AI output, tables).
 *   2. The "Guide for this page" side panel, which shows the matching section of /guide.html.
 *   3. The A-Z glossary on /guide.html (rendered into #glossary-list).
 *
 * Usage: <script src="/js/glossary.js" data-guide="incidents" defer></script>
 *   data-guide   section id of /guide.html to show in the side panel (omit to hide the panel button)
 * Everything is inserted with createElement/textContent; no HTML from data is ever parsed.
 */
(function () {
    'use strict';

    // cs: true = case-sensitive (abbreviations); patterns are regex sources, matched as whole words.
    const TERMS = [
        // ---------------------------------------------------------------- the setting
        { id: 'dmb', term: 'DMB (Deccan Meridian Bank)', cat: 'Setting', cs: true, p: ['DMB', 'Deccan Meridian Bank(?: Ltd)?'],
          def: 'The fictional bank used in this demo, headquartered in Hyderabad. All its data, vendors and incidents are synthetic.' },
        { id: 'tprm', term: 'TPRM', cat: 'Setting', cs: true, p: ['TPRM'],
          def: 'Third-Party Risk Management: how a bank controls the risk of relying on outside vendors, through contracts, audit reports, reviews and incident handling.' },
        { id: 'operational-resilience', term: 'Operational resilience', cat: 'Setting', p: ['operational resilience'],
          def: 'A bank\'s ability to keep its important services running, or restore them within an agreed time, when something fails.' },
        { id: 'rbi', term: 'RBI', cat: 'Regulation', cs: true, p: ['RBI'],
          def: 'Reserve Bank of India, the regulator for Indian banks. It sets the outsourcing and incident-reporting rules Fortivance helps banks follow.' },
        { id: 'rbi-directions', term: 'RBI Outsourcing Directions 2025', cat: 'Regulation', p: ['RBI Outsourcing Directions(?: 2025)?', 'Managing Risks in Outsourcing\\) Directions(?:, 2025)?', 'RBI Directions'],
          def: 'RBI (Commercial Banks - Managing Risks in Outsourcing) Directions, 2025: the rules for how banks select, contract and oversee outside service providers. Most vendor requirements in Fortivance come from it.' },
        { id: 'cert-in', term: 'CERT-In', cat: 'Regulation', cs: true, p: ['CERT-In'],
          def: 'Indian Computer Emergency Response Team, the national cyber-security agency. Certain cyber incidents must be reported to it. Fortivance never decides reportability; the analyst does.' },
        { id: 'vendor', term: 'Vendor (third party)', cat: 'Setting', p: ['third[- ]part(?:y|ies)'],
          def: 'An outside company the bank contracts with directly, for example a cloud provider or a payment switch.' },
        { id: 'fourth-party', term: 'Fourth party (subcontractor)', cat: 'Setting', p: ['fourth[- ]part(?:y|ies)', 'subcontractors?', 'sub-contractors?'],
          def: 'A vendor\'s own supplier. The bank has no contract with it, but a failure there still reaches the bank, as with SecureVault (NimbusCloud\'s data centre) or TextLink (NotifyHub\'s SMS partner).' },
        { id: 'risk-tier', term: 'Risk tier (CRITICAL / HIGH / MEDIUM / LOW)', cat: 'Vendors', p: ['risk tier', 'inherent tier'],
          def: 'How critical a vendor is to the bank, set by the bank when it takes the vendor on. CRITICAL means a failure would stop important customer services.' },
        { id: 'material', term: 'Material outsourcing', cat: 'Regulation', p: ['material outsourcing', 'materiality', 'non-material'],
          def: 'An outsourced service important enough that a failure would significantly affect the bank or its customers. Material arrangements get stricter oversight under RBI rules.' },
        { id: 'spof', term: 'Single point of failure', cat: 'Vendors', p: ['single[- ]point(?: of failure)?', 'SPOF'],
          def: 'A dependency with no alternative: if it fails, everything that relies on it fails too.' },

        // ---------------------------------------------------------------- vendors in the demo
        { id: 'nimbuscloud', term: 'NimbusCloud', cat: 'Demo vendors', p: ['NimbusCloud(?: Infra(?: Pvt Ltd)?)?'],
          def: 'Cloud hosting provider (CRITICAL). Hosts core banking, internet and mobile banking, onboarding and lending systems, so its outages reach most of the bank.' },
        { id: 'payrail', term: 'PayRail', cat: 'Demo vendors', p: ['PayRail(?: Switching Services(?: Pvt Ltd)?)?'],
          def: 'Payment switch (CRITICAL). Processes the bank\'s UPI and card transactions.' },
        { id: 'verikyc', term: 'VeriKYC', cat: 'Demo vendors', p: ['VeriKYC(?: Technologies(?: Pvt Ltd)?)?'],
          def: 'Identity-verification provider (HIGH). Runs video KYC and Aadhaar eKYC for account opening, so it holds customers\' personal data.' },
        { id: 'notifyhub', term: 'NotifyHub', cat: 'Demo vendors', p: ['NotifyHub(?: Communications(?: Pvt Ltd)?)?'],
          def: 'SMS and email provider (HIGH). Delivers OTPs and transaction alerts. As an SMS gateway it is outside the RBI IT outsourcing rules, but still monitored for incidents.' },
        { id: 'securevault', term: 'SecureVault', cat: 'Demo vendors', p: ['SecureVault(?: Datacentres(?: Pvt Ltd)?)?'],
          def: 'Data-centre operator used by NimbusCloud for its Mumbai region: a fourth party. Its cooling failure caused incident INC-NC-2026-1103.' },
        { id: 'textlink', term: 'TextLink', cat: 'Demo vendors', p: ['TextLink(?: Telecom(?: Gateway)?(?: Pvt Ltd)?)?'],
          def: 'SMS aggregator used by NotifyHub: a fourth party not disclosed in NotifyHub\'s contract. Its capacity problem caused incident NH-INC-2026-0412.' },

        // ---------------------------------------------------------------- systems and services
        { id: 'cbs', term: 'CBS', cat: 'Bank systems', cs: true, p: ['CBS'], def: 'Core Banking System: accounts, deposits, the general ledger and customer records. Almost every service depends on it.' },
        { id: 'ibp', term: 'IBP', cat: 'Bank systems', cs: true, p: ['IBP'], def: 'Internet Banking Portal: retail and business internet banking.' },
        { id: 'mbb', term: 'MBB', cat: 'Bank systems', cs: true, p: ['MBB'], def: 'Mobile Banking Backend: the servers and APIs behind the bank\'s mobile app.' },
        { id: 'upi', term: 'UPI / IMPS', cat: 'Bank systems', cs: true, p: ['UPI', 'IMPS'],
          def: 'India\'s instant payment systems (Unified Payments Interface and Immediate Payment Service). "UPI" is also the bank system that connects to the UPI network.' },
        { id: 'cms', term: 'CMS', cat: 'Bank systems', cs: true, p: ['CMS'], def: 'Card Management System: issues debit and credit cards and authorises card payments.' },
        { id: 'dop', term: 'DOP', cat: 'Bank systems', cs: true, p: ['DOP'], def: 'Digital Onboarding Platform: opens new accounts online using eKYC and video KYC.' },
        { id: 'los', term: 'LOS', cat: 'Bank systems', cs: true, p: ['LOS'], def: 'Loan Origination System: loan applications and underwriting.' },
        { id: 'cne', term: 'CNE', cat: 'Bank systems', cs: true, p: ['CNE'], def: 'Customer Notification Engine: sends OTPs, transaction alerts and statements. Payments cannot complete without the OTPs it sends.' },
        { id: 'application', term: 'Application', cat: 'Bank systems', p: ['applications?'],
          def: 'An internal bank system (for example CNE or CMS). Vendors support applications, and applications support business services.' },
        { id: 'business-service', term: 'Business service (SVC-…)', cat: 'Bank systems', cs: true, p: ['SVC-[A-Z]+', '[Bb]usiness services?'],
          def: 'What customers actually experience, such as UPI payments (SVC-PAY) or alerts and OTPs (SVC-ALERT). Impact is measured at this level.' },
        { id: 'otp', term: 'OTP', cat: 'Banking', cs: true, p: ['OTPs?'], def: 'One-time password sent by SMS to confirm a login or payment.' },
        { id: 'kyc', term: 'KYC / eKYC', cat: 'Banking', cs: true, p: ['e?KYC', 'Video KYC', 'video KYC', 'Aadhaar'],
          def: 'Know Your Customer: verifying a customer\'s identity. eKYC does it electronically using Aadhaar, India\'s national ID.' },

        // ---------------------------------------------------------------- incidents
        { id: 'incident-notice', term: 'Incident notice', cat: 'Incidents', p: ['incident notices?', 'vendor notices?'],
          def: 'A vendor\'s message telling the bank that something has gone wrong. Fortivance reads it, extracts the facts and traces the impact.' },
        { id: 'reference-no', term: 'Vendor reference number', cat: 'Incidents', cs: true, p: ['NH-INC-2026-0412', 'INC-NC-2026-1103', 'PR-SEC-2026-0027'],
          def: 'The vendor\'s own ticket number, kept so analysts can quote it: NH = NotifyHub, NC = NimbusCloud, PR-SEC = PayRail security.' },
        { id: 'severity', term: 'Severity', cat: 'Incidents', p: ['severity'],
          def: 'How serious the vendor says the incident is (CRITICAL, HIGH, MEDIUM, LOW). It is the vendor\'s view; the bank\'s own impact comes from the dependency trace.' },
        { id: 'detected-at', term: 'Detected at', cat: 'Incidents', p: ['detected at', 'detection time'],
          def: 'When the incident was first noticed. The RBI 6-hour reporting clock counts from this time.' },
        { id: 'production', term: 'Production', cat: 'Incidents', p: ['production(?: environment| systems?| status)?', 'non-production'],
          def: 'The live systems that serve real customers, as opposed to test environments (UAT). An incident in production is far more serious.' },
        { id: 'uat', term: 'UAT', cat: 'Incidents', cs: true, p: ['UAT'], def: 'User Acceptance Testing: a test environment. PayRail\'s suspected ransomware was found in UAT, not production.' },
        { id: 'ransomware', term: 'Ransomware', cat: 'Incidents', p: ['ransomware'], def: 'Malicious software that encrypts files and demands payment to unlock them.' },
        { id: 'edr', term: 'EDR', cat: 'Incidents', cs: true, p: ['EDR'], def: 'Endpoint Detection and Response: security software that spots attacks on servers and laptops.' },
        { id: 'containment', term: 'Containment', cat: 'Incidents', p: ['containment'], def: 'Steps that stop an incident from spreading, such as isolating affected servers.' },
        { id: 'customer-data-exposure', term: 'Customer data exposure', cat: 'Incidents', p: ['(?:customer )?data exposure'],
          def: 'Whether customer data may have been seen or taken: none stated, possible, confirmed or unknown. Exposure can trigger extra reporting duties.' },
        { id: 'reporting-window', term: 'RBI 6-hour reporting window', cat: 'Regulation', p: ['6-hour reporting window', '6-Hour Reporting Window', 'reporting window', 'six-hour', '6-hour'],
          def: 'Banks are expected to report significant cyber incidents to RBI within 6 hours of detection. Fortivance shows the countdown; "Window elapsed" means the 6 hours have passed.' },
        { id: 'reportability', term: 'Reportability', cat: 'Regulation', p: ['reportab(?:ility|le)'],
          def: 'Whether an incident must be reported to RBI or CERT-In. It is a legal judgement, so Fortivance never makes it; it lists it as a decision for the analyst.' },
        { id: 'impact-tolerance', term: 'Impact tolerance', cat: 'Resilience', p: ['impact tolerances?', 'tolerances?'],
          def: 'The longest a business service may be disrupted before the harm becomes unacceptable, set by the bank for each service (for example 1 hour for OTPs, 2 hours for payments).' },
        { id: 'disrupted', term: 'DISRUPTED', cat: 'Resilience', cs: true, p: ['DISRUPTED', 'Disrupted', 'disrupted'],
          def: 'An application the service cannot work without (an essential dependency) is affected, so the service is effectively down.' },
        { id: 'degraded', term: 'DEGRADED', cat: 'Resilience', cs: true, p: ['DEGRADED', 'Degraded', 'degraded', 'DEGRADATION', 'degradation'],
          def: 'Only a non-essential application is affected: the service still works, but worse (for example card payments without SMS alerts).' },
        { id: 'exposed', term: 'Exposed services', cat: 'Resilience', p: ['exposed', 'exposure'],
          def: 'All business services the incident reaches, disrupted or degraded. "2 disrupted / 4 exposed" means 4 are affected and 2 of them are down.' },
        { id: 'essential', term: 'Essential dependency', cat: 'Resilience', p: ['essential (?:dependency|dependencies|application|app)'],
          def: 'An application a service cannot work without. If it fails, the service is DISRUPTED; if a non-essential one fails, it is DEGRADED.' },
        { id: 'bcp', term: 'BCP (BCP-01 to BCP-06)', cat: 'Resilience', cs: true, p: ['BCP-0[1-6]', 'BCPs?', 'BCP/DR'],
          def: 'Business Continuity Plan: a pre-written playbook to keep a service running when something fails. BCP-01 Accounts, BCP-02 UPI/IMPS payments, BCP-03 Cards, BCP-04 Onboarding, BCP-05 Lending, BCP-06 Alerts and OTP.' },
        { id: 'dr', term: 'DR (disaster recovery)', cat: 'Resilience', cs: true, p: ['DR', '[Dd]isaster recovery'],
          def: 'Disaster recovery: restoring systems at a backup site, such as NimbusCloud\'s Chennai DR region.' },
        { id: 'failover', term: 'Failover', cat: 'Resilience', p: ['fail ?over'], def: 'Switching a system to its backup copy, often in another data centre, when the primary fails.' },
        { id: 'rto', term: 'RTO / RPO', cat: 'Resilience', cs: true, p: ['RTO', 'RPO'],
          def: 'Recovery Time Objective (how quickly a system must be restored) and Recovery Point Objective (how much recent data may be lost).' },
        { id: 'escalation', term: 'Escalation note', cat: 'Incidents', p: ['escalation note', 'escalation'],
          def: 'A short briefing for senior management, drafted by AI only after a human approves the response plan.' },
        { id: 'response-plan', term: 'Draft response plan', cat: 'Incidents', p: ['response plan', 'draft plan'],
          def: 'AI-drafted actions, questions for the vendor and decisions for the analyst. Nothing happens until an approver approves it.' },
        { id: 'analyst-approver', term: 'Analyst / Approver', cat: 'Incidents', p: ['analyst', 'approver'],
          def: 'The two roles. The analyst triages and reviews; the approver (head of third-party risk) approves or rejects the AI-drafted plan.' },
        { id: 'prompt-injection', term: 'Prompt injection', cat: 'AI safety', p: ['prompt[- ]injection', 'suspicious instructions?'],
          def: 'Hidden instructions in a document that try to make the AI misbehave. Fortivance treats notices as data, flags such text and withholds the plan.' },

        // ---------------------------------------------------------------- evidence
        { id: 'evidence', term: 'Evidence', cat: 'Evidence', p: ['evidence'],
          def: 'A passage in the vendor\'s own documents (contract, audit report) that proves a requirement is met.' },
        { id: 'requirement', term: 'Requirement (VS-…)', cat: 'Evidence', cs: true, p: ['[Rr]equirements?'],
          def: 'A control the bank expects from a vendor, taken from the RBI Directions or internal policy. VS = vendor standard; CON contract, BCP continuity, SEC security assurance.' },
        { id: 'vs-con-01', term: 'VS-CON-01 Audit and regulator access', cat: 'Evidence', cs: true, p: ['VS-CON-01'],
          def: 'The contract lets the bank, its auditors and RBI inspect the vendor and its subcontractors (RBI Directions para 69(xii)).' },
        { id: 'vs-con-02', term: 'VS-CON-02 Incident notification', cat: 'Evidence', cs: true, p: ['VS-CON-02'],
          def: 'The contract requires the vendor to report incidents to the bank within a set time, to a named recipient (para 56; 69(iii)).' },
        { id: 'vs-con-03', term: 'VS-CON-03 Control over subcontracting', cat: 'Evidence', cs: true, p: ['VS-CON-03'],
          def: 'Material subcontracting needs the bank\'s prior approval, and the contract\'s obligations pass down to subcontractors (para 4(2); 69(i), (xiii)).' },
        { id: 'vs-bcp-01', term: 'VS-BCP-01 Tested BCP/DR', cat: 'Evidence', cs: true, p: ['VS-BCP-01'],
          def: 'The vendor has a business continuity and disaster recovery plan, tests it regularly and shares the results (para 78, applying 40-43).' },
        { id: 'vs-sec-02', term: 'VS-SEC-02 Subservice organisations covered', cat: 'Evidence', cs: true, p: ['VS-SEC-02'],
          def: 'If the vendor\'s audit report leaves out a subcontractor, the bank gets separate assurance over it (internal policy).' },
        { id: 'soc2', term: 'SOC 2 Type II', cat: 'Evidence', cs: true, p: ['SOC ?2(?: Type (?:II|2))?'],
          def: 'An independent auditor\'s report on how well a vendor\'s security controls worked over a period, usually a year.' },
        { id: 'carve-out', term: 'Carve-out / subservice organisation', cat: 'Evidence', p: ['carve[- ]?outs?', 'carves? out', 'subservice organi[sz]ations?'],
          def: 'A carve-out is a part of the vendor\'s operation an audit report deliberately excludes, often a subcontractor (the "subservice organisation"). The bank must cover it another way.' },
        { id: 'assurance', term: 'Assurance', cat: 'Evidence', p: ['assurance'], def: 'Independent proof that controls work, such as an audit report or certificate.' },
        { id: 'found-partial-missing', term: 'FOUND / PARTIAL / MISSING', cat: 'Evidence', cs: true, p: ['FOUND', 'PARTIAL', 'MISSING', 'NOT[ _]ASSESSED'],
          def: 'Whether the vendor\'s documents fully meet, partly meet or do not meet a requirement. NOT ASSESSED means no document was analysed for it.' },
        { id: 'review-status', term: 'Needs review / Verified / Disputed', cat: 'Evidence', p: ['needs review', 'verified', 'disputed'],
          def: 'The human check of an AI finding: not yet checked, confirmed by an analyst, or overruled by one.' },
        { id: 'coverage', term: 'Coverage %', cat: 'Evidence', p: ['coverage'],
          def: 'The share of requirements with evidence FOUND. "Verified" coverage counts only findings an analyst has confirmed.' },
        { id: 'gap-report', term: 'Gap report', cat: 'Evidence', p: ['gap reports?', 'gaps?'],
          def: 'For one vendor: each requirement, the evidence found or missing, the cited passage, the review status and the risk score. Gaps are requirements without full evidence.' },
        { id: 'residual-risk', term: 'Residual risk score', cat: 'Evidence', p: ['residual risk(?: score)?', 'residual'],
          def: 'A 0-100 rule-based score (no AI): the vendor\'s inherent risk plus every gap not covered by evidence. 70+ HIGH, 40-69 MEDIUM, below 40 LOW.' },
        { id: 'inherent-risk', term: 'Inherent risk', cat: 'Evidence', p: ['inherent risk', 'inherent'],
          def: 'How risky a vendor is by its nature, before any controls: how critical it is and what data it handles.' },
        { id: 'data-access', term: 'Data access', cat: 'Evidence', cs: true, p: ['[Dd]ata [Aa]ccess', 'FINANCIAL', 'CUSTOMER_PII', 'PII'],
          def: 'What data the vendor handles: FINANCIAL (money and transactions), CUSTOMER_PII (personal information such as names and ID numbers), INTERNAL or NONE.' },
        { id: 'out-of-scope', term: 'Out of scope', cat: 'Evidence', p: ['out of scope', 'OUT OF SCOPE', 'in[- ]scope', 'IN-SCOPE'],
          def: 'The RBI IT outsourcing rules do not apply to this vendor (SMS gateways are excluded by para 5(2)(iii)), so no contract gap check runs; it is still monitored for incidents and continuity.' },
        { id: 'evidence-pack', term: 'Evidence pack', cat: 'Evidence', p: ['evidence pack'],
          def: 'A printable export of a vendor\'s gap report with citations, for auditors, regulators or the board.' },
        { id: 'citation', term: 'Citation', cat: 'Evidence', p: ['citations?', 'cited passages?', 'passages?'],
          def: 'The exact passage of the document the AI relied on. The system checks every cited passage exists and discards any that do not.' },
        { id: 'confidence', term: 'Confidence', cat: 'AI safety', p: ['confidence'],
          def: 'The AI\'s own estimate of how sure it is (0-100%). A guide for the reviewer, not proof.' },
        { id: 'periodic-review', term: 'Periodic review', cat: 'Vendors', p: ['periodic review', 'reassessment', 'review overdue'],
          def: 'The regular re-check of a vendor RBI expects (at least yearly for material vendors). An overdue review adds risk points.' },
        { id: 'iaas', term: 'IaaS', cat: 'Banking', cs: true, p: ['IaaS'], def: 'Infrastructure as a Service: renting servers, storage and networks from a cloud provider.' },
        { id: 'ist', term: 'IST', cat: 'Banking', cs: true, p: ['IST'], def: 'India Standard Time (UTC+5:30). All times in Fortivance are shown in IST.' },
        { id: 'mum-1', term: 'MUM-1', cat: 'Demo vendors', cs: true, p: ['MUM-1'], def: 'NimbusCloud\'s Mumbai cloud region, hosted in SecureVault\'s data centre.' }
    ];

    const BY_ID = new Map(TERMS.map(t => [t.id, t]));
    const GUIDE_URL = '/guide.html';
    const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'INPUT', 'SELECT', 'OPTION', 'BUTTON', 'A', 'SVG', 'svg', 'CANVAS', 'NOSCRIPT', 'TITLE', 'LABEL', 'SUMMARY', 'TH', 'H1']);
    const SKIP_SELECTOR = '.gl-term, .gl-tip, .gl-panel-head, .gl-help-btn, .nav-links, .nav-user-section, .brand-section, [data-no-glossary], .info-tip, .role-switch, #ai-loader, .ai-loader';
    const CONTAINER_SELECTOR = '.card, .risk-panel, section, .gl-panel-body, details, main';

    // ---------------------------------------------------------------- matching
    const WB_START = '(?<![A-Za-z0-9_-])';
    const WB_END = '(?![A-Za-z0-9_])';
    const compiled = TERMS.map(t => ({
        t,
        re: new RegExp(WB_START + '(?:' + t.p.join('|') + ')' + WB_END, t.cs ? 'g' : 'gi')
    }));

    /** Earliest (then longest) match of any term in text that is not yet marked in this container. */
    function firstMatch(text, marked) {
        let best = null;
        for (const c of compiled) {
            if (marked.has(c.t.id)) continue;
            c.re.lastIndex = 0;
            const m = c.re.exec(text);
            if (!m) continue;
            if (!best || m.index < best.index || (m.index === best.index && m[0].length > best.text.length)) {
                best = { index: m.index, text: m[0], t: c.t };
            }
        }
        return best;
    }

    // Per container: term id -> the span that marks it. A span that left the DOM frees the term again,
    // so content re-rendered into the same card is annotated afresh.
    const marks = new WeakMap();
    function markedIn(container) {
        let m = marks.get(container);
        if (!m) { m = new Map(); marks.set(container, m); }
        const live = new Set();
        for (const [id, span] of m) {
            if (span.isConnected && container.contains(span)) live.add(id); else m.delete(id);
        }
        return { map: m, live };
    }

    function skippable(el) {
        for (let n = el; n && n !== document.body; n = n.parentElement) {
            if (SKIP_TAGS.has(n.tagName) || n.isContentEditable) return true;
        }
        return !!(el.closest && el.closest(SKIP_SELECTOR));
    }

    function annotateTextNode(node) {
        const parent = node.parentElement;
        if (!parent || skippable(parent)) return;
        const container = parent.closest(CONTAINER_SELECTOR) || document.body;
        let current = node;
        // A text node may hold several different terms; wrap each one's first occurrence.
        for (let guard = 0; guard < 25 && current; guard++) {
            const text = current.nodeValue;
            if (!text || text.trim().length < 2) return;
            const { map, live } = markedIn(container);
            const m = firstMatch(text, live);
            if (!m) return;
            const before = current;
            const matchNode = before.splitText(m.index);
            const after = matchNode.splitText(m.text.length);
            const span = document.createElement('span');
            span.className = 'gl-term';
            span.tabIndex = 0;
            span.dataset.term = m.t.id;
            span.setAttribute('role', 'button');
            span.setAttribute('aria-label', m.text + ': ' + m.t.def);
            span.textContent = m.text;
            matchNode.replaceWith(span);
            map.set(m.t.id, span);
            current = after;
        }
    }

    function annotate(root) {
        if (!root) return;
        if (root.nodeType === Node.TEXT_NODE) { annotateTextNode(root); return; }
        if (root.nodeType !== Node.ELEMENT_NODE || skippable(root)) return;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode(n) { return n.nodeValue && n.nodeValue.trim().length > 1 ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT; }
        });
        const nodes = [];
        for (let n = walker.nextNode(); n; n = walker.nextNode()) nodes.push(n);
        nodes.forEach(annotateTextNode);
    }

    // ---------------------------------------------------------------- tooltip
    let tip = null, tipFor = null, hideTimer = null;

    function ensureTip() {
        if (tip) return tip;
        tip = document.createElement('div');
        tip.className = 'gl-tip';
        tip.id = 'gl-tip';
        tip.setAttribute('role', 'tooltip');
        tip.hidden = true;
        tip.addEventListener('mouseenter', () => clearTimeout(hideTimer));
        tip.addEventListener('mouseleave', scheduleHide);
        document.body.appendChild(tip);
        return tip;
    }

    function showTip(span) {
        const t = BY_ID.get(span.dataset.term);
        if (!t) return;
        clearTimeout(hideTimer);
        const el = ensureTip();
        el.replaceChildren();
        const h = document.createElement('div');
        h.className = 'gl-tip-term';
        h.textContent = t.term;
        const p = document.createElement('div');
        p.className = 'gl-tip-def';
        p.textContent = t.def;
        const a = document.createElement('a');
        a.className = 'gl-tip-link';
        a.href = GUIDE_URL + '#term-' + t.id;
        a.target = '_blank';
        a.rel = 'noopener';
        a.textContent = 'More in the guide →';
        el.append(h, p, a);
        el.hidden = false;
        tipFor = span;
        span.setAttribute('aria-describedby', 'gl-tip');

        const r = span.getBoundingClientRect();
        const w = Math.min(320, window.innerWidth - 24);
        el.style.width = w + 'px';
        let left = r.left + window.scrollX;
        left = Math.max(window.scrollX + 12, Math.min(left, window.scrollX + window.innerWidth - w - 12));
        el.style.left = left + 'px';
        const below = r.bottom + window.scrollY + 8;
        el.style.top = below + 'px';
        const tr = el.getBoundingClientRect();
        if (tr.bottom > window.innerHeight - 8 && r.top > tr.height + 16) {
            el.style.top = (r.top + window.scrollY - tr.height - 8) + 'px';
        }
    }

    function hideTip() {
        if (tip) tip.hidden = true;
        if (tipFor) tipFor.removeAttribute('aria-describedby');
        tipFor = null;
    }
    function scheduleHide() { clearTimeout(hideTimer); hideTimer = setTimeout(hideTip, 220); }

    function termFrom(e) { return e.target && e.target.closest ? e.target.closest('.gl-term') : null; }

    document.addEventListener('mouseover', e => { const s = termFrom(e); if (s) showTip(s); });
    document.addEventListener('mouseout', e => { if (termFrom(e)) scheduleHide(); });
    document.addEventListener('focusin', e => { const s = termFrom(e); if (s) showTip(s); });
    document.addEventListener('focusout', e => { if (termFrom(e)) scheduleHide(); });
    document.addEventListener('click', e => {
        const s = termFrom(e);
        if (s) {
            // A term inside a clickable row explains itself instead of opening the row.
            e.preventDefault();
            e.stopPropagation();
            if (tipFor === s && tip && !tip.hidden) hideTip(); else showTip(s);
        } else if (tip && !tip.contains(e.target)) {
            hideTip();
        }
    }, true);
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape') { hideTip(); closePanel(); }
        const s = termFrom(e);
        if (s && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); showTip(s); }
    });
    window.addEventListener('scroll', () => { if (tipFor) hideTip(); }, { passive: true });

    // ---------------------------------------------------------------- "Guide for this page" side panel
    let panel = null, panelBody = null, lastFocus = null;

    function closePanel() {
        if (!panel || panel.hidden) return;
        panel.hidden = true;
        document.body.classList.remove('gl-panel-open');
        if (lastFocus) lastFocus.focus();
    }

    async function openPanel(section) {
        lastFocus = document.activeElement;
        if (!panel) {
            panel = document.createElement('aside');
            panel.className = 'gl-panel';
            panel.setAttribute('aria-label', 'Guide for this page');
            const head = document.createElement('div');
            head.className = 'gl-panel-head';
            const title = document.createElement('strong');
            title.textContent = 'Guide for this page';
            const full = document.createElement('a');
            full.href = GUIDE_URL + '#' + section;
            full.target = '_blank';
            full.rel = 'noopener';
            full.textContent = 'Open full guide ↗';
            const close = document.createElement('button');
            close.type = 'button';
            close.className = 'gl-panel-close';
            close.setAttribute('aria-label', 'Close guide');
            close.textContent = '×';
            close.addEventListener('click', closePanel);
            head.append(title, full, close);
            panelBody = document.createElement('div');
            panelBody.className = 'gl-panel-body';
            panel.append(head, panelBody);
            document.body.appendChild(panel);
        }
        panel.hidden = false;
        document.body.classList.add('gl-panel-open');
        if (!panelBody.dataset.loaded) {
            panelBody.textContent = 'Loading guide…';
            try {
                const res = await window.fetch(GUIDE_URL, { credentials: 'same-origin' });
                const doc = new DOMParser().parseFromString(await res.text(), 'text/html');
                const src = doc.getElementById(section);
                panelBody.replaceChildren();
                if (src) {
                    panelBody.appendChild(document.importNode(src, true));
                    panelBody.dataset.loaded = '1';
                    annotate(panelBody);
                } else {
                    panelBody.textContent = 'This section of the guide could not be found.';
                }
            } catch (_) {
                panelBody.textContent = 'The guide could not be loaded. Open it from the Guide link in the menu.';
            }
        }
        panel.querySelector('.gl-panel-close').focus();
    }

    function addPanelButton(section) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'gl-help-btn';
        btn.setAttribute('aria-label', 'Open the guide for this page');
        const q = document.createElement('span');
        q.className = 'gl-help-q';
        q.textContent = '?';
        const label = document.createElement('span');
        label.textContent = 'Guide for this page';
        btn.append(q, label);
        btn.addEventListener('click', () => (panel && !panel.hidden ? closePanel() : openPanel(section)));
        document.body.appendChild(btn);
    }

    // ---------------------------------------------------------------- A-Z list on the guide page
    function renderGlossaryList(host) {
        const sorted = TERMS.slice().sort((a, b) => a.term.localeCompare(b.term, 'en', { sensitivity: 'base' }));
        const dl = document.createElement('dl');
        dl.className = 'gl-list';
        sorted.forEach(t => {
            const dt = document.createElement('dt');
            dt.id = 'term-' + t.id;
            dt.textContent = t.term;
            const cat = document.createElement('span');
            cat.className = 'gl-cat';
            cat.textContent = t.cat;
            dt.appendChild(cat);
            const dd = document.createElement('dd');
            dd.textContent = t.def;
            dl.append(dt, dd);
        });
        host.replaceChildren(dl);
        if (location.hash.startsWith('#term-')) {
            const target = document.getElementById(location.hash.slice(1));
            if (target) { target.classList.add('gl-highlight'); target.scrollIntoView({ block: 'center' }); }
        }
    }

    // ---------------------------------------------------------------- styles
    function injectStyles() {
        const css = `
.gl-term { border-bottom: 1px dotted currentColor; cursor: help; text-decoration: none; }
.gl-term:hover, .gl-term:focus-visible { border-bottom-style: solid; outline: none; background: rgba(13,148,136,0.08); border-radius: 2px; }
.gl-tip { position: absolute; z-index: 1000; padding: 0.65rem 0.8rem; border-radius: 8px; background: #0f2744; color: #fff;
  box-shadow: 0 10px 28px rgba(15,39,68,0.3); font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  font-size: 0.8125rem; font-style: normal; font-weight: 400; line-height: 1.5; text-align: left; text-transform: none; letter-spacing: normal; }
.gl-tip-term { font-weight: 700; margin-bottom: 0.25rem; }
.gl-tip-link { display: inline-block; margin-top: 0.4rem; color: #5eead4; font-weight: 600; text-decoration: none; }
.gl-tip-link:hover { text-decoration: underline; }
.gl-help-btn { position: fixed; right: 18px; bottom: 18px; z-index: 900; display: inline-flex; align-items: center; gap: 0.45rem;
  padding: 0.55rem 0.9rem 0.55rem 0.55rem; border: none; border-radius: 999px; background: #0f2744; color: #fff; font: inherit;
  font-size: 0.8125rem; font-weight: 600; cursor: pointer; box-shadow: 0 6px 18px rgba(15,39,68,0.3); }
.gl-help-btn:hover, .gl-help-btn:focus-visible { background: #0d9488; outline: none; }
.gl-help-q { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border-radius: 50%;
  background: #fff; color: #0f2744; font-weight: 800; }
.gl-panel { position: fixed; top: 0; right: 0; bottom: 0; z-index: 950; width: min(460px, 100vw); display: flex; flex-direction: column;
  background: #fff; border-left: 1px solid #e2e8f0; box-shadow: -12px 0 32px rgba(15,39,68,0.18); }
.gl-panel[hidden] { display: none; }
.gl-panel-head { display: flex; align-items: center; gap: 0.75rem; padding: 0.85rem 1rem; border-bottom: 1px solid #e2e8f0; background: #f8fafc; }
.gl-panel-head strong { color: #0f2744; flex: 1; }
.gl-panel-head a { font-size: 0.8125rem; color: #0d9488; text-decoration: none; font-weight: 600; }
.gl-panel-close { border: none; background: transparent; font-size: 1.5rem; line-height: 1; cursor: pointer; color: #475569; padding: 0 0.25rem; }
.gl-panel-body { overflow-y: auto; padding: 1rem 1.15rem 2rem; font-size: 0.875rem; line-height: 1.6; color: #1e293b; }
.gl-panel-body h2 { font-size: 1.1rem; margin: 0 0 0.6rem; color: #0f2744; }
.gl-panel-body h3 { font-size: 0.95rem; margin: 1.1rem 0 0.4rem; color: #0f2744; }
.gl-panel-body table { width: 100%; border-collapse: collapse; font-size: 0.8125rem; margin: 0.5rem 0; }
.gl-panel-body th, .gl-panel-body td { border-bottom: 1px solid #e2e8f0; padding: 0.35rem 0.4rem; text-align: left; vertical-align: top; }
.gl-panel-body ol, .gl-panel-body ul { padding-left: 1.2rem; }
@media (max-width: 640px) { .gl-help-btn span:last-child { display: none; } .gl-help-btn { padding: 0.5rem; } }
@media print { .gl-help-btn, .gl-panel, .gl-tip { display: none !important; } .gl-term { border-bottom: none; } }
`;
        const style = document.createElement('style');
        style.textContent = css;
        document.head.appendChild(style);
    }

    // ---------------------------------------------------------------- start
    const SCRIPT = document.currentScript;

    function start() {
        injectStyles();
        const section = SCRIPT && SCRIPT.dataset ? SCRIPT.dataset.guide : null;
        const host = document.getElementById('glossary-list');
        if (host) renderGlossaryList(host);
        if (section) addPanelButton(section);
        annotate(document.querySelector('main') || document.body);

        // Annotate content rendered later (API data, AI output, re-rendered tables).
        let pending = new Set(), timer = null;
        new MutationObserver(muts => {
            for (const m of muts) {
                const t = m.target.nodeType === Node.ELEMENT_NODE ? m.target : m.target.parentElement;
                if (t && t.closest && t.closest('.gl-tip, .gl-panel-head')) continue;
                m.addedNodes.forEach(n => {
                    if (n.nodeType === Node.ELEMENT_NODE && n.classList.contains('gl-term')) return;
                    pending.add(n);
                });
            }
            clearTimeout(timer);
            timer = setTimeout(() => {
                const batch = pending; pending = new Set();
                batch.forEach(n => { if (n.isConnected) annotate(n); });
            }, 120);
        }).observe(document.body, { childList: true, subtree: true });
    }

    window.FortivanceGlossary = { terms: TERMS, annotate };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
