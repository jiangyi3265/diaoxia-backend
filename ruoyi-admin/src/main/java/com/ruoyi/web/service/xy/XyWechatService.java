package com.ruoyi.web.service.xy;

import java.util.Map;
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
    private static final String SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";
    private final XyWechatProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();

    public XyWechatService(XyWechatProperties properties) { this.properties = properties; }

    public String[] exchangeCode(String code)
    {
        if (StringUtils.isEmpty(code)) throw new ServiceException("微信登录凭证不能为空");
        if (StringUtils.isEmpty(properties.getAppId()) || StringUtils.isEmpty(properties.getAppSecret()))
        {
            throw new ServiceException("微信登录未配置，请联系运营人员");
        }
        try
        {
            ResponseEntity<Map> response = restTemplate.getForEntity(SESSION_URL, Map.class, properties.getAppId(), properties.getAppSecret(), code);
            Map body = response.getBody();
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
            throw new ServiceException("微信登录服务暂不可用，请稍后重试");
        }
    }
}
