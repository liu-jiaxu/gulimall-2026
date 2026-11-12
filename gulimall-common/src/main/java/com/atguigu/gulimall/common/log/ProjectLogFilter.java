package com.atguigu.gulimall.common.log;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * 日志过滤器：只放行本项目（{@code com.atguigu} 包下）的日志，框架日志（spring / nacos / mybatis 等）一律丢弃。
 * <p>
 * 用于 logback.xml 中 appender 级的白名单过滤，例如：
 * <pre>
 * &lt;appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"&gt;
 *     &lt;filter class="com.atguigu.gulimall.common.log.ProjectLogFilter"/&gt;
 *     ...
 * &lt;/appender&gt;
 * </pre>
 * 说明：logback 1.5 起已移除 JaninoEventEvaluator，无法再用表达式过滤，故用自定义 Filter 按 logger 名判断。
 *
 * @author liujiaxu
 */
public class ProjectLogFilter extends Filter<ILoggingEvent> {

    /**
     * 放行的 logger 名前缀（本项目所有模块都在 com.atguigu.gulimall 下）
     */
    private static final String PROJECT_PACKAGE_PREFIX = "com.atguigu.gulimall";

    @Override
    public FilterReply decide(ILoggingEvent event) {
        String loggerName = event.getLoggerName();
        // logger 名以 com.atguigu.gulimall 开头 → 放行；否则（框架日志）→ 丢弃
        if (loggerName != null && loggerName.startsWith(PROJECT_PACKAGE_PREFIX)) {
            return FilterReply.ACCEPT;
        }
        return FilterReply.DENY;
    }
}
