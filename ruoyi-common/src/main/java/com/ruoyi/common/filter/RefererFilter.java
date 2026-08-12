package com.ruoyi.common.filter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 防盗链过滤器
 * 
 * @author ruoyi
 */
public class RefererFilter implements Filter
{
    /**
     * 允许的域名列表
     */
    private Set<String> allowedDomains = Collections.emptySet();

    @Override
    public void init(FilterConfig filterConfig) throws ServletException
    {
        String domains = filterConfig.getInitParameter("allowedDomains");
        if (domains == null)
        {
            return;
        }
        this.allowedDomains = Arrays.stream(domains.split(","))
                .map(String::trim)
                .filter(domain -> !domain.isEmpty())
                .map(this::normalizeConfiguredDomain)
                .filter(domain -> !domain.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException
    {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        String referer = req.getHeader("Referer");

        // 如果Referer为空，拒绝访问
        if (referer == null || referer.isEmpty())
        {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied: Referer header is required");
            return;
        }

        final String refererHost;
        try
        {
            refererHost = new URI(referer).getHost();
        }
        catch (URISyntaxException ex)
        {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied: invalid Referer header");
            return;
        }

        if (refererHost == null || !isAllowedHost(refererHost))
        {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied: Referer host is not allowed");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isAllowedHost(String host)
    {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        return allowedDomains.stream()
                .anyMatch(domain -> normalizedHost.equals(domain) || normalizedHost.endsWith("." + domain));
    }

    private String normalizeConfiguredDomain(String configuredDomain)
    {
        String normalized = configuredDomain.toLowerCase(Locale.ROOT);
        try
        {
            URI uri = normalized.contains("://") ? new URI(normalized) : new URI("https://" + normalized);
            return uri.getHost() == null ? "" : uri.getHost();
        }
        catch (URISyntaxException ex)
        {
            return "";
        }
    }

    @Override
    public void destroy()
    {

    }
}
