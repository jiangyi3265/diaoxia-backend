package com.ruoyi.web.controller.xy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;

class XyAdminControllerTest
{
    @Test
    void memberExportHasDedicatedPermissionRouteAndAuditLog() throws Exception
    {
        Method method = XyAdminController.class.getMethod(
                "exportMembers", HttpServletResponse.class, String.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        PostMapping postMapping = method.getAnnotation(PostMapping.class);
        Log log = method.getAnnotation(Log.class);

        assertNotNull(preAuthorize);
        assertEquals("@ss.hasPermi('xy:member:export')", preAuthorize.value());
        assertNotNull(postMapping);
        assertArrayEquals(new String[] { "/members/export" }, postMapping.value());
        assertNotNull(log);
        assertEquals("会员管理", log.title());
        assertEquals(BusinessType.EXPORT, log.businessType());
        assertFalse(log.isSaveRequestData());
        assertFalse(log.isSaveResponseData());
    }
}
