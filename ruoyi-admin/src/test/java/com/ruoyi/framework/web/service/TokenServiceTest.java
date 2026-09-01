package com.ruoyi.framework.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest
{
    private static final String TOKEN = "test-token";
    private static final String TOKEN_KEY = CacheConstants.LOGIN_TOKEN_KEY + TOKEN;

    @Mock
    private RedisCache redisCache;

    private TokenService tokenService;

    @BeforeEach
    void setUp()
    {
        tokenService = new TokenService();
        ReflectionTestUtils.setField(tokenService, "redisCache", redisCache);
    }

    @Test
    void refreshTokenStoresPermanentSessionWithoutRedisTtlWhenExpiryIsDisabled()
    {
        setConfiguredExpireMinutes(0);
        LoginUser loginUser = loginUser(null);

        tokenService.refreshToken(loginUser);

        assertEquals(Long.MAX_VALUE, loginUser.getExpireTime());
        assertTrue(loginUser.getLoginTime() > 0L);
        verify(redisCache).setCacheObject(TOKEN_KEY, loginUser);
        verifyNoMoreInteractions(redisCache);
    }

    @Test
    void verifyTokenMigratesExistingFiniteSessionToPermanentStorageOnce()
    {
        setConfiguredExpireMinutes(0);
        LoginUser loginUser = loginUser(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5));

        tokenService.verifyToken(loginUser);

        assertEquals(Long.MAX_VALUE, loginUser.getExpireTime());
        verify(redisCache).setCacheObject(TOKEN_KEY, loginUser);
        verifyNoMoreInteractions(redisCache);
    }

    @Test
    void verifyTokenDoesNotRewriteSessionThatIsAlreadyPermanent()
    {
        setConfiguredExpireMinutes(0);
        LoginUser loginUser = loginUser(Long.MAX_VALUE);

        tokenService.verifyToken(loginUser);

        assertEquals(Long.MAX_VALUE, loginUser.getExpireTime());
        verifyNoInteractions(redisCache);
    }

    @Test
    void refreshTokenKeepsThirtyMinuteRedisTtlWhenExpiryIsEnabled()
    {
        setConfiguredExpireMinutes(30);
        LoginUser loginUser = loginUser(null);

        tokenService.refreshToken(loginUser);

        assertEquals(loginUser.getLoginTime() + TimeUnit.MINUTES.toMillis(30), loginUser.getExpireTime());
        verify(redisCache).setCacheObject(TOKEN_KEY, loginUser, 30, TimeUnit.MINUTES);
        verifyNoMoreInteractions(redisCache);
    }

    @Test
    void verifyTokenMigratesPermanentSessionBackToConfiguredTtl()
    {
        setConfiguredExpireMinutes(30);
        LoginUser loginUser = loginUser(Long.MAX_VALUE);

        tokenService.verifyToken(loginUser);

        assertEquals(loginUser.getLoginTime() + TimeUnit.MINUTES.toMillis(30), loginUser.getExpireTime());
        verify(redisCache).setCacheObject(TOKEN_KEY, loginUser, 30, TimeUnit.MINUTES);
        verifyNoMoreInteractions(redisCache);
    }

    private LoginUser loginUser(Long expiresAt)
    {
        LoginUser loginUser = new LoginUser();
        loginUser.setToken(TOKEN);
        loginUser.setExpireTime(expiresAt);
        return loginUser;
    }

    private void setConfiguredExpireMinutes(int minutes)
    {
        ReflectionTestUtils.setField(tokenService, "expireTime", minutes);
    }
}
