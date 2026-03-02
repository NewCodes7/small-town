package com.newcodes7.small_town.global.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.MDC;
import org.slf4j.Marker;

/**
 * Prewarm 컨텍스트에서 INFO 이하 로그를 억제하는 Logback TurboFilter
 *
 * SearchPrewarmScheduler에서 MDC.put("prewarm", "true")를 설정하면
 * 해당 스레드의 INFO/DEBUG/TRACE 로그가 억제된다.
 * WARN/ERROR는 항상 통과.
 */
public class PrewarmLogFilter extends TurboFilter {

    public static final String MDC_KEY = "prewarm";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if ("true".equals(MDC.get(MDC_KEY)) && level.toInt() <= Level.INFO_INT) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }
}
