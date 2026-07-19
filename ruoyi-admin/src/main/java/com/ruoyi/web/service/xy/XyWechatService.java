package com.ruoyi.web.service.xy;

import java.util.Map;
import java.util.LinkedHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";
    private static final String SUBSCRIBE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token={token}";
    private static final DateTimeFormatter REMINDER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final XyWechatProperties properties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    public XyWechatService(XyWechatProperties properties, ObjectMapper objectMapper)
    {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(requestFactory);
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
            // RestTemplate 异常可能包含带 AppSecret 的完整请求地址，日志中不得输出异常明细。
            log.warn("微信 code2session 调用失败，异常类型={}", ex.getClass().getSimpleName());
            throw new ServiceException("微信登录服务暂不可用，请稍后重试");
        }
    }

    /** 订阅消息模板未配置时不请求用户授权，也不创建发送记录。 */
    public boolean isReservationReminderConfigured()
    {
        return !StringUtils.isEmpty(properties.getSubscribeReservationTemplateId());
    }

    public String getReservationReminderTemplateId()
    {
        return properties.getSubscribeReservationTemplateId();
    }

    /**
     * 发送预约订阅消息。成功返回 null，失败返回可记录的原因；模板字段名由部署环境配置。
     */
    @SuppressWarnings("unchecked")
    public String sendReservationReminder(String openid, String storeName, String seatCode, LocalDateTime reservationTime)
    {
        if (!isReservationReminderConfigured())
        {
            return "未配置预约订阅消息模板";
        }
        if (StringUtils.isEmpty(openid))
        {
            return "会员缺少微信 OpenID";
        }
        try
        {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(properties.getSubscribeReservationTitleField(), messageValue(limit("钓虾预约：" + storeName + " " + seatCode, 20)));
            data.put(properties.getSubscribeReservationTimeField(), messageValue(REMINDER_TIME.format(reservationTime)));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("touser", openid);
            payload.put("template_id", properties.getSubscribeReservationTemplateId());
            payload.put("page", properties.getSubscribeReservationPage());
            payload.put("lang", "zh_CN");
            payload.put("data", data);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
            ResponseEntity<String> response = restTemplate.postForEntity(SUBSCRIBE_SEND_URL,
                    request, String.class, accessToken());
            Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
            Number errorCode = body.get("errcode") instanceof Number ? (Number) body.get("errcode") : null;
            if (errorCode != null && errorCode.intValue() != 0)
            {
                String message = "微信订阅消息发送失败，errcode=" + errorCode + ", errmsg=" + body.get("errmsg");
                log.warn(message);
                return limit(message, 500);
            }
            return null;
        }
        catch (Exception ex)
        {
            // access_token 请求同样携带 AppSecret，避免把 URL/异常消息写入日志或业务表。
            log.warn("微信预约订阅消息发送失败，异常类型={}", ex.getClass().getSimpleName());
            return "微信订阅消息服务调用异常（" + ex.getClass().getSimpleName() + "）";
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized String accessToken() throws Exception
    {
        long now = System.currentTimeMillis();
        if (!StringUtils.isEmpty(accessToken) && now < accessTokenExpiresAt)
        {
            return accessToken;
        }
        ResponseEntity<String> response = restTemplate.getForEntity(ACCESS_TOKEN_URL, String.class,
                properties.getAppId(), properties.getAppSecret());
        Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
        Object token = body.get("access_token");
        if (StringUtils.isEmpty(token == null ? null : String.valueOf(token)))
        {
            throw new ServiceException("微信 access_token 获取失败：" + body.get("errmsg"));
        }
        Number expiresIn = body.get("expires_in") instanceof Number ? (Number) body.get("expires_in") : 7200;
        accessToken = String.valueOf(token);
        accessTokenExpiresAt = now + Math.max(60, expiresIn.longValue() - 120) * 1000L;
        return accessToken;
    }

    private Map<String, String> messageValue(String value)
    {
        Map<String, String> field = new LinkedHashMap<>();
        field.put("value", value);
        return field;
    }

    private String limit(String text, int maxLength)
    {
        if (text == null) return "";
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
