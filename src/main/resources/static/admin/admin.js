// Fortivance Admin Console Script
(function() {
    'use strict';

    // State
    let currentAccounts = [];
    let expandedUserIds = new Set();
    let activeFilter = 'ALL';
    let searchQuery = '';
    let showDeleted = false;
    let refreshTimer = null;
    let pendingDeleteAccount = null;
    let busyRows = 0;

    // Helper: extract CSRF token from cookie
    function csrfToken() {
        const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    // Helper: authenticated admin fetch with 404 redirect to /admin/login
    async function adminFetch(url, options = {}) {
        const headers = {
            'Accept': 'application/json',
            ...(options.headers || {})
        };
        if (options.method && options.method.toUpperCase() !== 'GET') {
            headers['X-XSRF-TOKEN'] = csrfToken();
        }
        try {
            const res = await window.fetch(url, {
                credentials: 'same-origin',
                ...options,
                headers
            });
            if (res.status === 404) {
                window.location.href = '/admin/login';
                return null;
            }
            return res;
        } catch (err) {
            showToast('Network error while communicating with server.', 'error');
            return null;
        }
    }

    // Toast notifications (replaces alert)
    function showToast(message, type = 'success') {
        const container = document.getElementById('toast-container');
        if (!container) return;

        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;

        const msgSpan = document.createElement('span');
        msgSpan.textContent = message;
        toast.appendChild(msgSpan);

        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.className = 'toast-close';
        closeBtn.textContent = '×';
        closeBtn.setAttribute('aria-label', 'Close');
        closeBtn.addEventListener('click', () => toast.remove());
        toast.appendChild(closeBtn);

        container.appendChild(toast);
        setTimeout(() => {
            if (toast.parentNode) toast.remove();
        }, 4000);
    }

    // Format IST date/time (e.g. "21 Nov, 6:05 PM")
    function fmtIST(val) {
        if (!val) return '-';
        try {
            const d = new Date(val);
            if (isNaN(d.getTime())) return '-';
            return d.toLocaleString('en-IN', {
                timeZone: 'Asia/Kolkata',
                day: 'numeric',
                month: 'short',
                hour: 'numeric',
                minute: '2-digit',
                hour12: true
            }).replace(/\b(am|pm)\b/i, s => s.toUpperCase());
        } catch (_) {
            return '-';
        }
    }

    // Format time left (e.g. "15 h 20 min", "Expired", or "-")
    function fmtTimeLeft(minutesLeft) {
        if (minutesLeft === null || minutesLeft === undefined) return '-';
        if (minutesLeft <= 0) return 'Expired';
        const h = Math.floor(minutesLeft / 60);
        const m = minutesLeft % 60;
        return `${h} h ${m} min`;
    }

    // Load admin profile (/api/admin/me)
    async function loadAdminMe() {
        const res = await adminFetch('/api/admin/me');
        if (!res) return;
        if (res.ok) {
            const data = await res.json().catch(() => ({}));
            const nameEl = document.getElementById('admin-user-name');
            if (nameEl) {
                nameEl.textContent = data.displayName || data.username || 'Admin';
            }
        }
    }

    // Update count cards
    function updateCounts(counts) {
        document.getElementById('count-active').textContent = counts.ACTIVE != null ? counts.ACTIVE : 0;
        document.getElementById('count-expiring').textContent = counts.EXPIRING_SOON != null ? counts.EXPIRING_SOON : 0;
        document.getElementById('count-expired').textContent = counts.EXPIRED != null ? counts.EXPIRED : 0;
        document.getElementById('count-disabled').textContent = counts.DISABLED != null ? counts.DISABLED : 0;
        document.getElementById('count-deleted').textContent = counts.DELETED != null ? counts.DELETED : 0;
    }

    // Update timestamp label
    function updateTimestamp(genTime) {
        const label = document.getElementById('updated-label');
        if (!label) return;
        const d = genTime ? new Date(genTime) : new Date();
        const timeStr = d.toLocaleTimeString('en-IN', { timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', second: '2-digit' });
        label.textContent = `Updated ${timeStr}`;
    }

    // Load accounts list
    async function loadAccounts() {
        const url = showDeleted ? '/api/admin/accounts?includeDeleted=true' : '/api/admin/accounts';
        const res = await adminFetch(url);
        if (!res) return;
        if (res.ok) {
            const data = await res.json().catch(() => ({}));
            currentAccounts = data.accounts || [];
            updateCounts(data.counts || {});
            updateTimestamp(data.generatedAt);
            renderTable();
        }
    }

    // Filter and search accounts
    function getFilteredAccounts() {
        return currentAccounts.filter(acc => {
            // Filter chip match
            if (activeFilter !== 'ALL') {
                const accState = (acc.state || '').toUpperCase();
                if (activeFilter === 'ACTIVE' && accState !== 'ACTIVE') return false;
                if (activeFilter === 'EXPIRING_SOON' && accState !== 'EXPIRING_SOON') return false;
                if (activeFilter === 'EXPIRED' && accState !== 'EXPIRED') return false;
                if (activeFilter === 'DISABLED' && accState !== 'DISABLED') return false;
                if (activeFilter === 'NOT_ACTIVATED' && accState !== 'NOT_ACTIVATED') return false;
            }

            // Search query match
            if (searchQuery) {
                const q = searchQuery.toLowerCase();
                const email = (acc.email || '').toLowerCase();
                const username = (acc.username || '').toLowerCase();
                const name = (acc.name || '').toLowerCase();
                if (!email.includes(q) && !username.includes(q) && !name.includes(q)) {
                    return false;
                }
            }

            return true;
        });
    }

    // Render Status Pill
    function createStatusPill(state) {
        const span = document.createElement('span');
        const st = (state || '').toUpperCase();
        if (st === 'ACTIVE') {
            span.className = 'pill pill-active';
            span.textContent = 'Active';
        } else if (st === 'EXPIRING_SOON') {
            span.className = 'pill pill-expiring';
            span.textContent = 'Expiring soon';
        } else if (st === 'EXPIRED') {
            span.className = 'pill pill-expired';
            span.textContent = 'Expired';
        } else if (st === 'DISABLED') {
            span.className = 'pill pill-disabled';
            span.textContent = 'Disabled';
        } else if (st === 'DELETED') {
            span.className = 'pill pill-deleted';
            span.textContent = 'Deleted';
        } else if (st === 'NOT_ACTIVATED') {
            span.className = 'pill pill-not-activated';
            span.textContent = 'Not activated';
        } else {
            span.className = 'pill pill-disabled';
            span.textContent = state || '-';
        }
        return span;
    }

    // Render Table
    function renderTable() {
        const tbody = document.getElementById('accounts-tbody');
        if (!tbody) return;
        tbody.replaceChildren();

        const accounts = getFilteredAccounts();
        if (accounts.length === 0) {
            const tr = document.createElement('tr');
            const td = document.createElement('td');
            td.colSpan = 10;
            td.style.textAlign = 'center';
            td.style.padding = '2.5rem 1rem';
            td.style.color = 'var(--text-secondary)';
            td.textContent = 'No matching accounts found.';
            tr.appendChild(td);
            tbody.appendChild(tr);
            return;
        }

        accounts.forEach(acc => {
            const isExpanded = expandedUserIds.has(acc.userId);

            // Main Row
            const tr = document.createElement('tr');
            tr.className = 'account-row';
            tr.setAttribute('data-user-id', String(acc.userId));

            // 1. Status
            const tdStatus = document.createElement('td');
            tdStatus.appendChild(createStatusPill(acc.state));
            tr.appendChild(tdStatus);

            // 2. Account (Username + Test Tag)
            const tdAccount = document.createElement('td');
            const userSpan = document.createElement('span');
            userSpan.style.fontWeight = '600';
            userSpan.textContent = acc.username || '-';
            tdAccount.appendChild(userSpan);
            if (acc.type === 'TEST') {
                const testTag = document.createElement('span');
                testTag.className = 'tag-test';
                testTag.textContent = 'Test account';
                tdAccount.appendChild(testTag);
            }
            tr.appendChild(tdAccount);

            // 3. Name / Organisation
            const tdName = document.createElement('td');
            tdName.textContent = acc.name || '-';
            tr.appendChild(tdName);

            // 4. Role
            const tdRole = document.createElement('td');
            tdRole.textContent = acc.role || '-';
            tr.appendChild(tdRole);

            // 5. Requested
            const tdReq = document.createElement('td');
            tdReq.textContent = fmtIST(acc.requestedAt);
            tr.appendChild(tdReq);

            // 6. Access until
            const tdUntil = document.createElement('td');
            tdUntil.textContent = fmtIST(acc.accessExpiresAt);
            tr.appendChild(tdUntil);

            // 7. Time left
            const tdTimeLeft = document.createElement('td');
            tdTimeLeft.textContent = fmtTimeLeft(acc.minutesLeft);
            if (acc.minutesLeft != null && acc.minutesLeft <= 360 && acc.minutesLeft > 0) {
                tdTimeLeft.style.color = 'var(--status-amber)';
                tdTimeLeft.style.fontWeight = '600';
            } else if (acc.minutesLeft != null && acc.minutesLeft <= 0) {
                tdTimeLeft.style.color = 'var(--status-red)';
            }
            tr.appendChild(tdTimeLeft);

            // 8. Extensions
            const tdExt = document.createElement('td');
            if (acc.type === 'TEST') {
                tdExt.textContent = '-';
            } else {
                const extCount = acc.extensionCount != null ? acc.extensionCount : 0;
                const maxExt = acc.maxExtensions != null ? acc.maxExtensions : 2;
                let text = `${extCount} of ${maxExt}`;
                if (acc.adminExtensionHours && acc.adminExtensionHours > 0) {
                    text += ` (+${acc.adminExtensionHours} h by admin)`;
                }
                tdExt.textContent = text;
            }
            tr.appendChild(tdExt);

            // 9. Last sign-in
            const tdLogin = document.createElement('td');
            tdLogin.textContent = fmtIST(acc.lastLoginAt);
            tr.appendChild(tdLogin);

            // 10. Actions
            const tdActions = document.createElement('td');
            tdActions.style.textAlign = 'right';

            const actionGroup = document.createElement('div');
            actionGroup.className = 'action-group';

            const isDeleted = acc.state === 'DELETED';
            const isTest = acc.type === 'TEST';

            // Details Toggle button
            const detailsBtn = document.createElement('button');
            detailsBtn.type = 'button';
            detailsBtn.className = 'btn-xs btn-xs-secondary';
            detailsBtn.textContent = isExpanded ? 'Hide' : 'Details';
            detailsBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                toggleExpand(acc.userId);
            });
            actionGroup.appendChild(detailsBtn);

            // Extend Button / Menu (DEMO only, not deleted)
            if (!isTest && !isDeleted) {
                const adminHours = acc.adminExtensionHours || 0;
                const remainingAllowance = 48 - adminHours;

                if (remainingAllowance <= 0) {
                    const usedSpan = document.createElement('span');
                    usedSpan.style.fontSize = '0.72rem';
                    usedSpan.style.color = 'var(--text-muted)';
                    usedSpan.textContent = 'Admin extension used (48 h)';
                    actionGroup.appendChild(usedSpan);
                } else {
                    const dropdown = document.createElement('div');
                    dropdown.className = 'extend-dropdown';

                    const extendBtn = document.createElement('button');
                    extendBtn.type = 'button';
                    extendBtn.className = 'btn-xs btn-xs-primary';
                    extendBtn.textContent = 'Extend ▾';
                    extendBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        closeAllExtendMenus();
                        const menu = dropdown.querySelector('.extend-menu');
                        if (menu) menu.style.display = menu.style.display === 'block' ? 'none' : 'block';
                    });
                    dropdown.appendChild(extendBtn);

                    const menu = document.createElement('div');
                    menu.className = 'extend-menu';

                    // 24 h option
                    if (remainingAllowance >= 24) {
                        const opt24 = document.createElement('button');
                        opt24.type = 'button';
                        opt24.textContent = '24 h';
                        opt24.addEventListener('click', (e) => {
                            e.stopPropagation();
                            menu.style.display = 'none';
                            executeExtend(acc, 24);
                        });
                        menu.appendChild(opt24);
                    }

                    // 48 h option
                    if (remainingAllowance >= 48) {
                        const opt48 = document.createElement('button');
                        opt48.type = 'button';
                        opt48.textContent = '48 h';
                        opt48.addEventListener('click', (e) => {
                            e.stopPropagation();
                            menu.style.display = 'none';
                            executeExtend(acc, 48);
                        });
                        menu.appendChild(opt48);
                    }

                    dropdown.appendChild(menu);
                    actionGroup.appendChild(dropdown);
                }
            }

            // Disable / Enable Button (DEMO & TEST, not deleted)
            if (!isDeleted) {
                const disableBtn = document.createElement('button');
                disableBtn.type = 'button';
                if (acc.active) {
                    disableBtn.className = 'btn-xs btn-xs-secondary';
                    disableBtn.textContent = 'Disable';
                    disableBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        executeDisable(acc);
                    });
                } else {
                    disableBtn.className = 'btn-xs btn-xs-success';
                    disableBtn.textContent = 'Enable';
                    disableBtn.addEventListener('click', (e) => {
                        e.stopPropagation();
                        executeEnable(acc);
                    });
                }
                actionGroup.appendChild(disableBtn);
            }

            // Delete Button (DEMO only, not deleted)
            if (!isTest && !isDeleted) {
                const deleteBtn = document.createElement('button');
                deleteBtn.type = 'button';
                deleteBtn.className = 'btn-xs btn-xs-danger';
                deleteBtn.textContent = 'Delete';
                deleteBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    openDeleteModal(acc);
                });
                actionGroup.appendChild(deleteBtn);
            }

            tdActions.appendChild(actionGroup);
            tr.appendChild(tdActions);

            // Click row to toggle expansion
            tr.addEventListener('click', () => {
                toggleExpand(acc.userId);
            });

            tbody.appendChild(tr);

            // Expanded Detail Row
            if (isExpanded) {
                const trDetail = document.createElement('tr');
                trDetail.className = 'detail-row';

                const tdDetail = document.createElement('td');
                tdDetail.colSpan = 10;

                const detailWrap = document.createElement('div');
                detailWrap.className = 'detail-content';

                // Left meta
                const leftMeta = document.createElement('div');
                leftMeta.style.display = 'grid';
                leftMeta.style.gap = '0.5rem';

                const phoneRow = document.createElement('div');
                const phoneLabel = document.createElement('strong');
                phoneLabel.textContent = 'Phone: ';
                phoneRow.appendChild(phoneLabel);
                const phoneSpan = document.createElement('span');
                phoneSpan.textContent = acc.phone || 'Not provided';
                phoneRow.appendChild(phoneSpan);
                leftMeta.appendChild(phoneRow);

                const idRow = document.createElement('div');
                const idLabel = document.createElement('strong');
                idLabel.textContent = 'Account IDs: ';
                idRow.appendChild(idLabel);
                const idSpan = document.createElement('span');
                idSpan.textContent = `User #${acc.userId}` + (acc.requestId ? `, Request #${acc.requestId}` : '');
                idRow.appendChild(idSpan);
                leftMeta.appendChild(idRow);

                if (acc.deletedAt) {
                    const delRow = document.createElement('div');
                    const delLabel = document.createElement('strong');
                    delLabel.textContent = 'Deleted at: ';
                    delRow.appendChild(delLabel);
                    const delSpan = document.createElement('span');
                    delSpan.textContent = fmtIST(acc.deletedAt);
                    delRow.appendChild(delSpan);
                    leftMeta.appendChild(delRow);
                }

                detailWrap.appendChild(leftMeta);

                // Right purpose
                const rightPurpose = document.createElement('div');
                const purposeLabel = document.createElement('div');
                purposeLabel.style.fontWeight = '600';
                purposeLabel.style.marginBottom = '0.35rem';
                purposeLabel.textContent = 'Request Purpose:';
                rightPurpose.appendChild(purposeLabel);

                const purposeBox = document.createElement('div');
                purposeBox.className = 'detail-purpose-box';
                purposeBox.textContent = acc.purpose || 'No purpose recorded.';
                rightPurpose.appendChild(purposeBox);

                detailWrap.appendChild(rightPurpose);
                tdDetail.appendChild(detailWrap);
                trDetail.appendChild(tdDetail);
                tbody.appendChild(trDetail);
            }
        });
    }

    // Toggle expand state
    function toggleExpand(userId) {
        if (expandedUserIds.has(userId)) {
            expandedUserIds.delete(userId);
        } else {
            expandedUserIds.add(userId);
        }
        renderTable();
    }

    // Close all open extend menus
    function closeAllExtendMenus() {
        document.querySelectorAll('.extend-menu').forEach(m => m.style.display = 'none');
    }

    document.addEventListener('click', () => {
        closeAllExtendMenus();
    });

    // Helper: disable/enable buttons in row during in-flight action
    function setRowBusy(userId, isBusy) {
        const row = document.querySelector(`tr.account-row[data-user-id="${userId}"]`);
        busyRows = Math.max(0, busyRows + (isBusy ? 1 : -1));
        if (!row) return;
        row.querySelectorAll('button').forEach(btn => btn.disabled = isBusy);
    }

    // Execute Extend action
    async function executeExtend(acc, hours) {
        setRowBusy(acc.userId, true);
        try {
            const res = await adminFetch(`/api/admin/accounts/${acc.userId}/extend`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ hours })
            });
            if (!res) return;
            const data = await res.json().catch(() => ({}));
            if (res.status === 200) {
                updateAccountInList(data);
                showToast(`Extended ${acc.username} by ${hours} h`, 'success');
            } else {
                showToast(data.error || `Extend failed (status ${res.status})`, 'error');
            }
        } finally {
            setRowBusy(acc.userId, false);
        }
    }

    // Execute Disable action
    async function executeDisable(acc) {
        const confirmed = window.confirm(`Disable ${acc.username}? They will be signed out immediately.`);
        if (!confirmed) return;

        setRowBusy(acc.userId, true);
        try {
            const res = await adminFetch(`/api/admin/accounts/${acc.userId}/disable`, {
                method: 'POST'
            });
            if (!res) return;
            const data = await res.json().catch(() => ({}));
            if (res.status === 200) {
                updateAccountInList(data);
                showToast(`Disabled ${acc.username}`, 'success');
            } else {
                showToast(data.error || `Disable failed (status ${res.status})`, 'error');
            }
        } finally {
            setRowBusy(acc.userId, false);
        }
    }

    // Execute Enable action
    async function executeEnable(acc) {
        setRowBusy(acc.userId, true);
        try {
            const res = await adminFetch(`/api/admin/accounts/${acc.userId}/enable`, {
                method: 'POST'
            });
            if (!res) return;
            const data = await res.json().catch(() => ({}));
            if (res.status === 200) {
                updateAccountInList(data);
                showToast(`Enabled ${acc.username}`, 'success');
            } else {
                showToast(data.error || `Enable failed (status ${res.status})`, 'error');
            }
        } finally {
            setRowBusy(acc.userId, false);
        }
    }

    // Update single account in state without full reload
    function updateAccountInList(updatedAcc) {
        if (!updatedAcc || !updatedAcc.userId) return;
        const idx = currentAccounts.findIndex(a => a.userId === updatedAcc.userId);
        if (idx !== -1) {
            currentAccounts[idx] = updatedAcc;
        } else {
            currentAccounts.unshift(updatedAcc);
        }
        renderTable();
    }

    // Delete Modal Logic
    const modal = document.getElementById('delete-modal');
    const modalText = document.getElementById('delete-modal-text');
    const modalExpected = document.getElementById('delete-modal-expected');
    const modalInput = document.getElementById('delete-modal-input');
    const confirmBtn = document.getElementById('delete-confirm-btn');
    const cancelBtn = document.getElementById('delete-cancel-btn');
    const modalError = document.createElement('p');
    modalError.setAttribute('role', 'alert');
    modalError.style.cssText = 'display:none; margin:0.5rem 0 0; font-size:0.8125rem; color:var(--status-red);';
    if (modalInput) modalInput.insertAdjacentElement('afterend', modalError);

    function setModalError(msg) {
        modalError.textContent = msg || '';
        modalError.style.display = msg ? 'block' : 'none';
        if (modalInput) modalInput.style.borderColor = msg ? 'var(--status-red)' : '';
    }

    function openDeleteModal(acc) {
        pendingDeleteAccount = acc;
        modalText.textContent = `Delete ${acc.username}? They can never sign in with this account again. The request record is kept and this email may request a new demo.`;
        modalExpected.textContent = acc.username;
        modalInput.value = '';
        setModalError('');
        confirmBtn.disabled = true;
        modal.style.display = 'flex';
        modalInput.focus();
    }

    function closeDeleteModal() {
        pendingDeleteAccount = null;
        modal.style.display = 'none';
    }

    if (modalInput) {
        modalInput.addEventListener('input', () => {
            if (!pendingDeleteAccount) return;
            setModalError('');
            confirmBtn.disabled = modalInput.value.trim() === '';
        });
        modalInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !confirmBtn.disabled) confirmBtn.click();
        });
    }

    if (cancelBtn) {
        cancelBtn.addEventListener('click', closeDeleteModal);
    }
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && pendingDeleteAccount) closeDeleteModal();
    });
    if (modal) {
        modal.addEventListener('click', (e) => { if (e.target === modal) closeDeleteModal(); });
    }

    if (confirmBtn) {
        confirmBtn.addEventListener('click', async () => {
            if (!pendingDeleteAccount) return;
            if (modalInput.value.trim() !== pendingDeleteAccount.username) {
                setModalError('That does not match the username. Type ' + pendingDeleteAccount.username + ' exactly.');
                modalInput.focus();
                modalInput.select();
                return;
            }
            const acc = pendingDeleteAccount;
            closeDeleteModal();

            setRowBusy(acc.userId, true);
            try {
                const res = await adminFetch(`/api/admin/accounts/${acc.userId}`, {
                    method: 'DELETE'
                });
                if (!res) return;
                const data = await res.json().catch(() => ({}));
                if (res.status === 200) {
                    showToast(`Deleted ${acc.username}`, 'success');
                    await loadAccounts();
                } else {
                    showToast(data.error || `Delete failed (status ${res.status})`, 'error');
                }
            } finally {
                setRowBusy(acc.userId, false);
            }
        });
    }

    // Toolbar Event Listeners
    // Filter chips
    const chipContainer = document.getElementById('filter-chips');
    if (chipContainer) {
        chipContainer.querySelectorAll('.admin-filter-chip').forEach(chip => {
            chip.addEventListener('click', () => {
                chipContainer.querySelectorAll('.admin-filter-chip').forEach(c => c.classList.remove('active'));
                chip.classList.add('active');
                activeFilter = chip.getAttribute('data-filter') || 'ALL';
                renderTable();
            });
        });
    }

    // Search input
    const searchInput = document.getElementById('search-input');
    if (searchInput) {
        searchInput.addEventListener('input', () => {
            searchQuery = (searchInput.value || '').trim();
            renderTable();
        });
    }

    // Show deleted checkbox
    const showDeletedCheckbox = document.getElementById('show-deleted-checkbox');
    if (showDeletedCheckbox) {
        showDeletedCheckbox.addEventListener('change', () => {
            showDeleted = showDeletedCheckbox.checked;
            loadAccounts();
        });
    }

    // Refresh button
    const refreshBtn = document.getElementById('refresh-btn');
    if (refreshBtn) {
        refreshBtn.addEventListener('click', async () => {
            await loadAccounts();
            showToast('Accounts refreshed');
        });
    }

    // Auto-refresh every 60 seconds
    function startAutoRefresh() {
        if (refreshTimer) clearInterval(refreshTimer);
        refreshTimer = setInterval(() => {
            // Skip while an action is running, the delete dialog is open or an extend menu is open.
            const menuOpen = Array.from(document.querySelectorAll('.extend-menu')).some(m => m.style.display === 'block');
            if (busyRows > 0 || pendingDeleteAccount || menuOpen) return;
            loadAccounts();
        }, 60000);
    }

    // Initialise console
    async function init() {
        await loadAdminMe();
        await loadAccounts();
        startAutoRefresh();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
