/**
 * 검색 관련 유틸리티 함수
 * - 한글 자모 분리
 * - 검색 기록 관리
 */

// ===== 한글 자모 분리 유틸리티 =====
const HANGUL_BASE = 0xAC00;
const HANGUL_END = 0xD7A3;
const CHOSUNG = ['ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];
const JUNGSUNG = ['ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ', 'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'];
const JONGSUNG = ['', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'];

/**
 * 한글 문자 여부 확인
 * @param {string} char - 확인할 문자
 * @returns {boolean} 한글 여부
 */
function isHangul(char) {
    const code = char.charCodeAt(0);
    return code >= HANGUL_BASE && code <= HANGUL_END;
}

/**
 * 한글 문자열을 자모로 분리
 * 예: "리액트" → "ㄹㅣㅇㅐㅋㅡㅌㅡ"
 * @param {string} text - 분리할 문자열
 * @returns {string} 자모로 분리된 문자열
 */
function decomposeHangul(text) {
    if (!text) return text;

    let result = '';
    for (let char of text) {
        if (isHangul(char)) {
            const code = char.charCodeAt(0) - HANGUL_BASE;
            const chosungIndex = Math.floor(code / (21 * 28));
            const jungsungIndex = Math.floor((code % (21 * 28)) / 28);
            const jongsungIndex = code % 28;

            result += CHOSUNG[chosungIndex];
            result += JUNGSUNG[jungsungIndex];
            if (jongsungIndex !== 0) {
                result += JONGSUNG[jongsungIndex];
            }
        } else {
            result += char;
        }
    }
    return result;
}

// ===== 검색 기록 관리 =====
const MAX_HISTORY = 20;

/**
 * 검색 기록 클래스
 */
class SearchHistory {
    constructor(storageKey) {
        this.storageKey = storageKey;
    }

    /**
     * 검색 기록 가져오기
     * @returns {string[]} 검색 기록 배열
     */
    getHistory() {
        const history = localStorage.getItem(this.storageKey);
        return history ? JSON.parse(history) : [];
    }

    /**
     * 검색 기록 저장
     * @param {string} query - 저장할 검색어
     */
    saveHistory(query) {
        let history = this.getHistory();
        // 중복 제거
        history = history.filter(item => item !== query);
        // 맨 앞에 추가
        history.unshift(query);
        // 최대 개수 제한
        history = history.slice(0, MAX_HISTORY);
        localStorage.setItem(this.storageKey, JSON.stringify(history));
    }

    /**
     * 검색 기록 필터링 (자모 분리 검색 지원)
     * @param {string} query - 검색어 (없으면 최근 기록 반환)
     * @param {number} limit - 반환할 최대 개수
     * @returns {string[]} 필터링된 검색 기록
     */
    filterHistory(query, limit = 2) {
        const history = this.getHistory();
        if (!query) return history.slice(0, 6);

        // 자모 분리하여 비교
        const decomposedQuery = decomposeHangul(query.toLowerCase());

        return history.filter(item => {
            const decomposedItem = decomposeHangul(item.toLowerCase());
            return decomposedItem.includes(decomposedQuery);
        }).slice(0, limit);
    }

    /**
     * 검색 기록 삭제
     */
    clearHistory() {
        localStorage.removeItem(this.storageKey);
    }
}
