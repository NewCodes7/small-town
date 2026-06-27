// ============================================
// Admin Notifications (사이드바 알림)
// ============================================

(function () {
    const toggle = document.getElementById('adminNotificationToggle');
    const badge = document.getElementById('adminNotificationBadge');
    const panel = document.getElementById('adminNotificationPanel');
    const listEl = document.getElementById('adminNotificationList');
    const markAllBtn = document.getElementById('adminNotificationMarkAllBtn');

    if (!toggle || !badge || !panel || !listEl) {
        return;
    }

    const POLL_INTERVAL_MS = 60000;

    function updateBadge(count) {
        const value = Number(count) || 0;
        if (value > 0) {
            badge.textContent = value > 99 ? '99+' : value;
            badge.classList.remove('d-none');
        } else {
            badge.classList.add('d-none');
        }
    }

    async function refreshUnreadCount() {
        try {
            const res = await fetch('/api/admin/notifications/unread-count');
            if (!res.ok) {
                return;
            }
            const data = await res.json();
            updateBadge(data.unreadCount);
        } catch (error) {
            console.error('알림 개수 조회 실패', error);
        }
    }

    function formatDate(value) {
        if (!value) {
            return '';
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return value;
        }
        return date.toLocaleString('ko-KR', { hour12: false });
    }

    function buildDeepLink(notification) {
        if (notification.referenceType === 'CRAWLING_RUN' && notification.referenceId) {
            return `/admin/articles?tab=crawlinglogs&runId=${notification.referenceId}`;
        }
        return null;
    }

    async function markAsRead(id) {
        try {
            const res = await fetch(`/api/admin/notifications/${id}/read`, { method: 'POST' });
            if (res.ok) {
                const data = await res.json();
                updateBadge(data.unreadCount);
            }
        } catch (error) {
            console.error('알림 읽음 처리 실패', error);
        }
    }

    function renderNotification(notification) {
        const item = document.createElement('a');
        const deepLink = buildDeepLink(notification);
        item.href = deepLink || '#';
        item.className = 'list-group-item list-group-item-action';
        if (!notification.isRead) {
            item.classList.add('list-group-item-warning');
        }

        const header = document.createElement('div');
        header.className = 'd-flex justify-content-between align-items-start';

        const title = document.createElement('div');
        title.className = 'fw-bold';
        title.textContent = notification.title || '(제목 없음)';
        header.appendChild(title);

        const time = document.createElement('small');
        time.className = 'text-muted ms-2 text-nowrap';
        time.textContent = formatDate(notification.createdAt);
        header.appendChild(time);

        item.appendChild(header);

        if (notification.message) {
            const message = document.createElement('div');
            message.className = 'small text-muted';
            message.style.whiteSpace = 'pre-line';
            message.textContent = notification.message;
            item.appendChild(message);
        }

        item.addEventListener('click', async (event) => {
            if (!deepLink) {
                event.preventDefault();
            }
            if (!notification.isRead) {
                await markAsRead(notification.id);
            }
            // deepLink가 있으면 기본 동작(이동)이 진행된다.
        });

        return item;
    }

    async function loadNotifications() {
        listEl.innerHTML = '<div class="text-center text-muted py-4">불러오는 중...</div>';
        try {
            const res = await fetch('/api/admin/notifications?page=0&size=30');
            if (!res.ok) {
                throw new Error('알림을 불러오지 못했습니다.');
            }
            const data = await res.json();
            updateBadge(data.unreadCount);

            const notifications = data.notifications || [];
            if (notifications.length === 0) {
                listEl.innerHTML = '<div class="text-center text-muted py-4">알림이 없습니다.</div>';
                return;
            }

            const group = document.createElement('div');
            group.className = 'list-group';
            notifications.forEach(notification => {
                group.appendChild(renderNotification(notification));
            });
            listEl.innerHTML = '';
            listEl.appendChild(group);
        } catch (error) {
            console.error(error);
            listEl.innerHTML = '<div class="text-center text-danger py-4">알림을 불러오지 못했습니다.</div>';
        }
    }

    if (markAllBtn) {
        markAllBtn.addEventListener('click', async () => {
            markAllBtn.disabled = true;
            try {
                const res = await fetch('/api/admin/notifications/read-all', { method: 'POST' });
                if (res.ok) {
                    const data = await res.json();
                    updateBadge(data.unreadCount);
                }
                await loadNotifications();
            } catch (error) {
                console.error('전체 읽음 처리 실패', error);
            } finally {
                markAllBtn.disabled = false;
            }
        });
    }

    panel.addEventListener('show.bs.offcanvas', loadNotifications);

    refreshUnreadCount();
    setInterval(refreshUnreadCount, POLL_INTERVAL_MS);
})();
