package com.newcodes7.small_town.global.util;

import jakarta.servlet.http.HttpServletRequest;

public class Client {
    /**
     * nginx가 모든 location에서 X-Real-IP를 항상 $remote_addr로 덮어써서 전달하므로 최우선 신뢰.
     * X-Forwarded-For는 nginx가 클라이언트가 보낸 값 뒤에 $proxy_add_x_forwarded_for로 실제 IP를
     * append하는 구조라, 첫 번째 값이 아니라 마지막 값이 신뢰 가능한 홉이다
     * (첫 번째 값을 쓰면 클라이언트가 임의로 조작해 IP 기반 제한을 우회할 수 있음).
     */
    public static String getClientIpAddress(HttpServletRequest request) {
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            String[] ips = xForwardedFor.split(",");
            return ips[ips.length - 1].trim();
        }

        return request.getRemoteAddr();
    }
}
