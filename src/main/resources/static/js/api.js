const USE_MOCK = false;
window.USE_MOCK = USE_MOCK;

function fmtDate(value) {
    if (!value) return '-';
    try {
        const d = new Date(value);
        if (isNaN(d.getTime())) return '-';
        return d.toLocaleString('en-IN', {
            timeZone: 'Asia/Kolkata',
            day: '2-digit',
            month: 'short',
            year: 'numeric',
            hour: '2-digit',
            minute: '2-digit'
        });
    } catch (_) {
        return '-';
    }
}
window.fmtDate = fmtDate;

function fmtDay(value) {
    if (!value) return '-';
    const str = String(value).trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(str)) {
        const parts = str.split('-');
        const y = parseInt(parts[0], 10);
        const m = parseInt(parts[1], 10) - 1;
        const d = parseInt(parts[2], 10);
        const date = new Date(y, m, d);
        return date.toLocaleDateString('en-IN', {
            day: '2-digit',
            month: 'short',
            year: 'numeric'
        });
    }
    return fmtDate(value);
}
window.fmtDay = fmtDay;

function escapeHtml(str) {
    if (str == null) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
window.escapeHtml = escapeHtml;

const api = {
    escapeHtml,
    fmtDate,
    fmtDay,

    async parseError(res, fallback) {
        if (!res) return fallback;
        try {
            const data = await res.json();
            if (data && typeof data === 'object') {
                if (data.message) return data.message;
                if (data.error) return data.error;
            } else if (typeof data === 'string') {
                return data;
            }
        } catch (_) {
            // not JSON
        }
        return fallback;
    },

    async fetch(url, options = {}) {
        const config = {
            credentials: 'include',
            headers: {
                'Accept': 'application/json',
                ...options.headers
            },
            ...options
        };

        if (USE_MOCK) {
            if (url === '/api/incidents') {
                url = '/mock/incidents.json';
            } else if (url.startsWith('/api/incidents/')) {
                url = '/mock/incident-detail.json';
            }
        }

        const res = await window.fetch(url, config);
        if (res.status === 401) {
            // DemoExpiryFilter answers {"expired": true} when a demo account's access has run out.
            let expired = false;
            try {
                expired = (await res.clone().json()).expired === true;
            } catch (_) {
                // not JSON
            }
            window.location.href = expired ? '/login.html?error=expired' : '/login.html';
            return null;
        }
        return res;
    },

    async get(url) {
        const res = await this.fetch(url, { method: 'GET' });
        if (!res || !res.ok) {
            const defaultMsg = `GET ${url} failed with status ${res ? res.status : 'network error'}`;
            const msg = await this.parseError(res, defaultMsg);
            const err = new Error(msg);
            err.status = res ? res.status : null;
            throw err;
        }
        return res.json();
    },

    async post(url, body) {
        const isFormData = body instanceof FormData;
        const headers = isFormData ? {} : { 'Content-Type': 'application/json' };
        const res = await this.fetch(url, {
            method: 'POST',
            headers,
            body: isFormData ? body : JSON.stringify(body)
        });
        if (!res || !res.ok) {
            const defaultMsg = `POST ${url} failed with status ${res ? res.status : 'network error'}`;
            const msg = await this.parseError(res, defaultMsg);
            const err = new Error(msg);
            err.status = res ? res.status : null;
            throw err;
        }
        return res.json();
    },

    async patch(url, body) {
        const res = await this.fetch(url, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (!res || !res.ok) {
            const defaultMsg = `PATCH ${url} failed with status ${res ? res.status : 'network error'}`;
            const msg = await this.parseError(res, defaultMsg);
            const err = new Error(msg);
            err.status = res ? res.status : null;
            throw err;
        }
        return res.json();
    },

    async me() {
        try {
            return await this.get('/api/me');
        } catch (e) {
            return null;
        }
    },

    async initNav(activeKey) {
        const user = await this.me();
        const userSlot = document.getElementById('nav-user');
        if (userSlot && user) {
            let roleHtml = '';
            if (user.canSwitchRole) {
                const isApprover = String(user.role).toUpperCase().includes('APPROVER');
                roleHtml = `
                    <div class="role-switch" id="role-switch" role="group" aria-label="Switch Role">
                        <button type="button" class="role-switch-btn ${!isApprover ? 'active' : ''}" data-role="ANALYST" aria-pressed="${!isApprover}" ${!isApprover ? 'disabled' : ''} title="${!isApprover ? 'Current role: Analyst' : 'Switch to Analyst'}">Analyst</button>
                        <button type="button" class="role-switch-btn ${isApprover ? 'active' : ''}" data-role="APPROVER" aria-pressed="${isApprover}" ${isApprover ? 'disabled' : ''} title="${isApprover ? 'Current role: Approver' : 'Switch to Approver'}">Approver</button>
                    </div>
                `;
            } else {
                roleHtml = `<span class="badge badge-role">${escapeHtml(user.role)}</span>`;
            }

            userSlot.innerHTML = `
                <a href="/profile.html" class="user-name">${escapeHtml(user.displayName || user.username)}</a>
                ${roleHtml}
                <a href="/logout" class="nav-logout" id="nav-logout-btn">Logout</a>
            `;

            const roleSwitch = document.getElementById('role-switch');
            if (roleSwitch) {
                roleSwitch.querySelectorAll('.role-switch-btn').forEach(btn => {
                    btn.addEventListener('click', async () => {
                        const targetRole = btn.getAttribute('data-role');
                        if (!targetRole || btn.classList.contains('active')) return;

                        roleSwitch.querySelectorAll('.role-switch-btn').forEach(b => b.disabled = true);

                        try {
                            // api.fetch sends an ended/expired session to the login page instead of alerting.
                            const res = await api.fetch('/api/me/role', {
                                method: 'POST',
                                headers: {
                                    'Content-Type': 'application/json',
                                    'Accept': 'application/json'
                                },
                                body: JSON.stringify({ role: targetRole })
                            });
                            if (!res) return;

                            if (res.status === 200) {
                                window.location.reload();
                                return;
                            }

                            let errData = {};
                            try {
                                errData = await res.json();
                            } catch (_) {}

                            const errMsg = errData.error || `Failed to switch role (status ${res.status})`;
                            alert(errMsg);
                        } catch (err) {
                            alert('Network error while switching role.');
                        } finally {
                            roleSwitch.querySelectorAll('.role-switch-btn').forEach(b => {
                                b.disabled = b.classList.contains('active');
                            });
                        }
                    });
                });
            }

            const logoutBtn = document.getElementById('nav-logout-btn');
            if (logoutBtn) {
                logoutBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    window.location.replace('/logout');
                });
            }
        }
        // Admin panel link, only for admin accounts (the server enforces access as well).
        const navLinks = document.querySelector('.nav-links');
        if (user && user.isAdmin && navLinks && !navLinks.querySelector('a[data-nav="admin"]')) {
            const adminLink = document.createElement('a');
            adminLink.href = '/admin.html';
            adminLink.dataset.nav = 'admin';
            adminLink.textContent = 'Admin';
            navLinks.appendChild(adminLink);
        }
        if (activeKey) {
            const activeLink = document.querySelector(`.nav-links a[data-nav="${activeKey}"]`);
            if (activeLink) activeLink.classList.add('active');
        }
        return user;
    }
};
