            // Open video modal
            function openVideoModal(element) {
                const videoId = element.getAttribute('data-video-id');
                const videoTitle = element.getAttribute('data-video-title');
                const articleId = element.getAttribute('data-article-id');
                const modal = document.getElementById('videoModal');
                const iframe = document.getElementById('modalVideoPlayer');

                // Increment view count
                if (articleId) {
                    fetch(`/video/api/videos/${articleId}/view`, {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                        },
                        credentials: 'same-origin',
                        redirect: 'manual'
                    }).catch(error => {
                        console.log('조회수 증가 요청 실패:', error);
                    });
                }

                // Set iframe src with autoplay
                iframe.src = `https://www.youtube.com/embed/${videoId}?controls=1&modestbranding=1&rel=0&autoplay=1`;
                iframe.title = videoTitle;

                // Show modal
                modal.classList.add('active');
                document.body.style.overflow = 'hidden'; // Prevent background scrolling
            }

            // Close video modal
            function closeVideoModal(event) {
                const modal = document.getElementById('videoModal');
                const iframe = document.getElementById('modalVideoPlayer');

                // Only close if clicking on modal background or close button
                if (event.target === modal || event.target.classList.contains('video-modal-close')) {
                    modal.classList.remove('active');
                    iframe.src = ''; // Stop video playback
                    document.body.style.overflow = ''; // Restore scrolling
                }
            }

            // Close modal with Escape key
            document.addEventListener('keydown', function(event) {
                if (event.key === 'Escape') {
                    const modal = document.getElementById('videoModal');
                    if (modal.classList.contains('active')) {
                        closeVideoModal({ target: modal });
                    }
                }
            });

            // 블로그 좋아요 토글
            async function toggleArticleLike(button, event) {
                event.preventDefault();
                event.stopPropagation();

                const articleId = button.getAttribute('data-article-id');
                const icon = button.querySelector('.like-icon');
                const countSpan = button.querySelector('.like-count');

                try {
                    const response = await fetch(`/api/articles/${articleId}/like`, {
                        method: 'POST',
                        credentials: 'include'
                    });

                    if (response.ok) {
                        const data = await response.json();

                        // Update UI
                        countSpan.textContent = data.likeCount;

                        if (data.isLiked) {
                            button.classList.add('liked');
                            icon.classList.remove('far');
                            icon.classList.add('fas');
                        } else {
                            button.classList.remove('liked');
                            icon.classList.remove('fas');
                            icon.classList.add('far');
                        }
                    } else if (response.status === 401) {
                        alert('좋아요를 하려면 로그인이 필요합니다.');
                    }
                } catch (error) {
                    console.error('좋아요 처리 실패:', error);
                }
            }

            // 비디오 좋아요 토글
            async function toggleVideoLike(button, event) {
                event.preventDefault();
                event.stopPropagation();

                const videoId = button.getAttribute('data-video-id');
                const icon = button.querySelector('.like-icon');
                const countSpan = button.querySelector('.like-count');

                try {
                    const response = await fetch(`/video/api/videos/${videoId}/like`, {
                        method: 'POST',
                        credentials: 'include'
                    });

                    if (response.ok) {
                        const data = await response.json();

                        // Update UI
                        countSpan.textContent = data.likeCount;

                        if (data.isLiked) {
                            button.classList.add('liked');
                            icon.classList.remove('far');
                            icon.classList.add('fas');
                        } else {
                            button.classList.remove('liked');
                            icon.classList.remove('fas');
                            icon.classList.add('far');
                        }
                    } else if (response.status === 401) {
                        alert('좋아요를 하려면 로그인이 필요합니다.');
                    }
                } catch (error) {
                    console.error('좋아요 처리 실패:', error);
                }
            }

            // 좋아요 상태 로드
            async function loadLikeStatus() {
                // Load article like status
                const articleButtons = document.querySelectorAll('.like-button[data-article-id]');
                for (const button of articleButtons) {
                    const articleId = button.getAttribute('data-article-id');
                    try {
                        const response = await fetch(`/api/articles/${articleId}/like-status`, {
                            credentials: 'include'
                        });
                        if (response.ok) {
                            const data = await response.json();
                            const icon = button.querySelector('.like-icon');
                            const countSpan = button.querySelector('.like-count');

                            countSpan.textContent = data.likeCount;

                            if (data.hasLiked) {
                                button.classList.add('liked');
                                icon.classList.remove('far');
                                icon.classList.add('fas');
                            }
                        }
                    } catch (error) {
                        console.error('Article like status load failed:', error);
                    }
                }

                // Load video like status
                const videoButtons = document.querySelectorAll('.like-button[data-video-id]');
                for (const button of videoButtons) {
                    const videoId = button.getAttribute('data-video-id');
                    try {
                        const response = await fetch(`/video/api/videos/${videoId}/like-status`, {
                            credentials: 'include'
                        });
                        if (response.ok) {
                            const data = await response.json();
                            const icon = button.querySelector('.like-icon');
                            const countSpan = button.querySelector('.like-count');

                            countSpan.textContent = data.likeCount;

                            if (data.hasLiked) {
                                button.classList.add('liked');
                                icon.classList.remove('far');
                                icon.classList.add('fas');
                            }
                        }
                    } catch (error) {
                        console.error('Video like status load failed:', error);
                    }
                }
            }

            // 상대 시간 계산 함수
            function getRelativeTime(dateString) {
                const now = new Date();
                const date = new Date(dateString);
                const diffInSeconds = Math.floor((now - date) / 1000);

                const minute = 60;
                const hour = minute * 60;
                const day = hour * 24;
                const week = day * 7;
                const month = day * 30;
                const year = day * 365;

                if (diffInSeconds < minute) {
                    return '방금 전';
                } else if (diffInSeconds < hour) {
                    const minutes = Math.floor(diffInSeconds / minute);
                    return `${minutes}분 전`;
                } else if (diffInSeconds < day) {
                    const hours = Math.floor(diffInSeconds / hour);
                    return `${hours}시간 전`;
                } else if (diffInSeconds < week) {
                    const days = Math.floor(diffInSeconds / day);
                    return `${days}일 전`;
                } else if (diffInSeconds < month) {
                    const weeks = Math.floor(diffInSeconds / week);
                    return `${weeks}주일 전`;
                } else if (diffInSeconds < year) {
                    const months = Math.floor(diffInSeconds / month);
                    return `${months}달 전`;
                } else {
                    const years = Math.floor(diffInSeconds / year);
                    return `${years}년 전`;
                }
            }

            // 날짜 포맷팅 함수
            function formatDate(dateString) {
                const date = new Date(dateString);
                return date.toLocaleDateString('ko-KR', {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit'
                });
            }

            // 페이지 로드 시 상대 시간 적용 및 좋아요 상태 로드
            document.addEventListener('DOMContentLoaded', function() {
                // 상대 시간 표시
                document.querySelectorAll('.relative-time').forEach(element => {
                    const dateString = element.getAttribute('data-date');
                    if (dateString) {
                        // 상대 시간 표시
                        element.textContent = getRelativeTime(dateString);
                        // 툴팁에 포맷된 날짜 표시
                        element.title = formatDate(dateString);
                    }
                });

                // 좋아요 상태 로드
                loadLikeStatus();

                // 검색 자동완성 초기화
                initSearchAutocomplete();
            });

            // ===== 검색 자동완성 기능 =====
            function initSearchAutocomplete() {
                const searchHistory = new SearchHistory('small_town_search_history');
                const searchInput = document.getElementById('searchInput');
                const autocompleteDropdown = document.getElementById('autocompleteDropdown');
                const searchForm = document.getElementById('searchForm');

                if (!searchInput || !autocompleteDropdown || !searchForm) return;

                let autocompleteDebounce;
                let selectedIndex = -1;
                let suggestions = [];
                let lastInputValue = '';
                let programmaticChange = false;

                // 검색창 입력 이벤트
                searchInput.addEventListener('input', function() {
                    if (programmaticChange) {
                        programmaticChange = false;
                        clearTimeout(autocompleteDebounce);
                        return;
                    }

                    const query = this.value.trim();
                    lastInputValue = this.value;
                    clearTimeout(autocompleteDebounce);
                    selectedIndex = -1;

                    if (query.length < 1) {
                        displaySearchHistory();
                        return;
                    }

                    autocompleteDebounce = setTimeout(() => {
                        fetchAutocompleteSuggestions(query);
                    }, 50);
                });

                // 키보드 방향키 이벤트
                searchInput.addEventListener('keydown', function(e) {
                    const items = autocompleteDropdown.querySelectorAll('.autocomplete-item');
                    if (!autocompleteDropdown || autocompleteDropdown.style.display === 'none' || items.length === 0) {
                        return;
                    }

                    if (e.key === 'ArrowDown') {
                        e.preventDefault();
                        selectedIndex = selectedIndex < items.length - 1 ? selectedIndex + 1 : 0;
                        updateSelection(items);
                    } else if (e.key === 'ArrowUp') {
                        e.preventDefault();
                        selectedIndex = selectedIndex > 0 ? selectedIndex - 1 : items.length - 1;
                        updateSelection(items);
                    } else if (e.key === 'Enter') {
                        if (selectedIndex >= 0 && selectedIndex < items.length) {
                            e.preventDefault();
                            items[selectedIndex].click();
                        }
                    } else if (e.key === 'Escape') {
                        autocompleteDropdown.style.display = 'none';
                        selectedIndex = -1;
                    }
                });

                // 포커스 잃으면 드롭다운 숨기기
                searchInput.addEventListener('blur', function() {
                    setTimeout(() => {
                        autocompleteDropdown.style.display = 'none';
                        selectedIndex = -1;
                    }, 200);
                });

                // 포커스 얻으면 드롭다운 다시 보이기
                searchInput.addEventListener('focus', function() {
                    const query = this.value.trim();
                    if (query.length >= 1 && suggestions.length > 0) {
                        autocompleteDropdown.style.display = 'block';
                    } else if (query.length === 0) {
                        displaySearchHistory();
                    }
                });

                // 폼 제출 시 검색 기록 저장
                searchForm.addEventListener('submit', function(e) {
                    const query = searchInput.value.trim();
                    if (query.length > 0) {
                        searchHistory.saveHistory(query);
                    }
                });

                function displaySearchHistory() {
                    const history = searchHistory.getHistory().slice(0, 6);
                    if (history.length === 0) {
                        autocompleteDropdown.style.display = 'none';
                        return;
                    }

                    let html = '';
                    history.forEach((item, index) => {
                        html += `
                            <div class="autocomplete-item history-item" data-type="history" data-term="${item}" data-index="${index}">
                                <i class="fas fa-clock-rotate-left" style="color: #9ca3af; margin-right: 12px;"></i>
                                <span class="autocomplete-term">${item}</span>
                            </div>
                        `;
                    });

                    autocompleteDropdown.innerHTML = html;
                    autocompleteDropdown.style.display = 'block';

                    const items = autocompleteDropdown.querySelectorAll('.autocomplete-item');
                    items.forEach(item => {
                        item.addEventListener('click', function() {
                            const term = this.dataset.term;
                            searchInput.value = term;
                            searchHistory.saveHistory(term);
                            autocompleteDropdown.style.display = 'none';
                            searchForm.submit();
                        });
                    });

                    if (selectedIndex >= 0 && selectedIndex < items.length) {
                        updateSelection(items);
                    }
                }

                function fetchAutocompleteSuggestions(query) {
                    fetch(`/api/autocomplete?q=${encodeURIComponent(query)}`)
                        .then(response => response.json())
                        .then(data => {
                            suggestions = data.slice(0, 6);
                            displayAutocompleteSuggestions(query, suggestions);
                        })
                        .catch(error => {
                            console.error('자동완성 조회 오류:', error);
                        });
                }

                function displayAutocompleteSuggestions(query, data) {
                    const matchedHistory = searchHistory.filterHistory(query, 2);
                    let html = '';
                    let currentIndex = 0;

                    matchedHistory.forEach((item) => {
                        html += `
                            <div class="autocomplete-item history-item" data-type="history" data-term="${item}" data-index="${currentIndex}">
                                <i class="fas fa-clock-rotate-left" style="color: #9ca3af; margin-right: 12px;"></i>
                                <span class="autocomplete-term">${item}</span>
                            </div>
                        `;
                        currentIndex++;
                    });

                    if (data && data.length > 0) {
                        data.forEach((item) => {
                            if (item.type === 'corporation') {
                                html += `
                                    <div class="autocomplete-item corporation-item" data-type="corporation" data-id="${item.id}" data-name="${item.name}" data-index="${currentIndex}">
                                        <div style="display: flex; align-items: center; gap: 12px;">
                                            ${item.logoUrl ?
                                                `<img src="${item.logoUrl}" alt="${item.name}" style="width: 24px; height: 24px; border-radius: 4px; object-fit: contain;">` :
                                                `<i class="fas fa-building" style="width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; color: var(--primary-green); font-size: 18px;"></i>`
                                            }
                                            <span class="autocomplete-term">${item.name}</span>
                                        </div>
                                    </div>
                                `;
                            } else {
                                html += `
                                    <div class="autocomplete-item term-item" data-type="term" data-term="${item.term}" data-index="${currentIndex}">
                                        <span class="autocomplete-term">${item.term}</span>
                                    </div>
                                `;
                            }
                            currentIndex++;
                        });
                    }

                    if (html === '') {
                        autocompleteDropdown.style.display = 'none';
                        return;
                    }

                    autocompleteDropdown.innerHTML = html;
                    autocompleteDropdown.style.display = 'block';

                    const items = autocompleteDropdown.querySelectorAll('.autocomplete-item');
                    items.forEach(item => {
                        item.addEventListener('click', function() {
                            if (this.dataset.type === 'corporation') {
                                const corporationId = this.dataset.id;
                                window.location.href = `/corporations/${corporationId}`;
                            } else {
                                const term = this.dataset.term;
                                searchInput.value = term;
                                searchHistory.saveHistory(term);
                                autocompleteDropdown.style.display = 'none';
                                searchForm.submit();
                            }
                        });
                    });

                    if (selectedIndex >= 0 && selectedIndex < items.length) {
                        updateSelection(items);
                    }
                }

                function updateSelection(items) {
                    items.forEach((item, index) => {
                        if (index === selectedIndex) {
                            item.classList.add('selected');
                            item.scrollIntoView({ block: 'nearest' });
                        } else {
                            item.classList.remove('selected');
                        }
                    });

                    if (selectedIndex >= 0 && selectedIndex < items.length) {
                        const selectedItem = items[selectedIndex];
                        if (selectedItem.dataset.type === 'corporation') {
                            programmaticChange = true;
                            searchInput.value = selectedItem.dataset.name;
                        } else if (selectedItem.dataset.type === 'term' || selectedItem.dataset.type === 'history') {
                            programmaticChange = true;
                            searchInput.value = selectedItem.dataset.term;
                        }
                    } else {
                        programmaticChange = true;
                        searchInput.value = lastInputValue;
                    }
                }
            }
