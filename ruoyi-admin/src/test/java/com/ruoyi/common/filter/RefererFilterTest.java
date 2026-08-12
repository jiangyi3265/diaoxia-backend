package com.ruoyi.common.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RefererFilterTest
{
    private RefererFilter filter;

    @BeforeEach
    void setUp() throws Exception
    {
        MockFilterConfig config = new MockFilterConfig();
        config.addInitParameter("allowedDomains",
                "admin.example.com, https://api.example.com, servicewechat.com");
        filter = new RefererFilter();
        filter.init(config);
    }

    @Test
    void acceptsExactAndSubdomainHosts() throws Exception
    {
        assertAllowed("https://admin.example.com/profile/image.jpg");
        assertAllowed("https://foo.servicewechat.com/page-frame.html");
    }

    @Test
    void rejectsLookalikeAndMissingHosts() throws Exception
    {
        assertRejected("https://admin.example.com.attacker.test/profile/image.jpg");
        assertRejected(null);
    }

    @Test
    void rejectsMalformedReferer() throws Exception
    {
        assertRejected("not a valid URI");
    }

    private void assertAllowed(String referer) throws Exception
    {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/profile/image.jpg");
        request.addHeader("Referer", referer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    private void assertRejected(String referer) throws Exception
    {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/profile/image.jpg");
        if (referer != null)
        {
            request.addHeader("Referer", referer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(403, response.getStatus());
    }
}
