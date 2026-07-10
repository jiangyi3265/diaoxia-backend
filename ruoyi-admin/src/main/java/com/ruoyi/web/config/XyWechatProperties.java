package com.ruoyi.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 微信小程序密钥只允许从部署环境变量注入，禁止提交真实值到仓库。 */
@Component
@ConfigurationProperties(prefix = "xy.wechat")
public class XyWechatProperties
{
    private String appId;
    private String appSecret;

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
}
