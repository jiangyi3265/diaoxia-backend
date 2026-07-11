package com.ruoyi.web.service.xy;

import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.web.config.XyWechatProperties;

/** 微信小程序 code2session 客户端。 */
@Service
public class XyWechatService
{
    private static final Logger log = LoggerFactory.getLogger(XyWechatService.class);
    private static final String SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";
    private final XyWechatProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public XyWechatService(XyWechatProperties properties, ObjectMapper objectMapper)
    {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String[] exchangeCode(String code)
    {
        if (StringUtils.isEmpty(code)) throw new ServiceException("微信登录凭证不能为空");
        if (StringUtils.isEmpty(properties.getAppId()) || StringUtils.isEmpty(properties.getAppSecret()))
        {
            throw new ServiceException("微信登录未配置，请联系运营人员");
        }
        try
        {
            // 微信 code2session 会以 text/plain 返回 JSON，先读取原文再解析。
            ResponseEntity<String> response = restTemplate.getForEntity(SESSION_URL, String.class, properties.getAppId(), properties.getAppSecret(), code);
            Map body = objectMapper.readValue(response.getBody(), Map.class);
            if (body == null || StringUtils.isEmpty((String) body.get("openid")))
            {
                throw new ServiceException("微信登录失败，请稍后重试");
            }
            return new String[] {(String) body.get("openid"), (String) body.get("unionid")};
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            log.error("微信 code2session 调用失败", ex);
            throw new ServiceException("微信登录服务暂不可用，请稍后重试");
        }
    }
}
