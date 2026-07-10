package com.ruoyi.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 微信支付商户配置，只允许通过部署环境注入敏感值。 */
@Component
@ConfigurationProperties(prefix = "xy.wechat-pay")
public class XyWechatPayProperties
{
    private String mchId;
    private String merchantSerialNo;
    private String privateKeyPath;
    private String notifyUrl;
    private String refundNotifyUrl;
    private String appId;
    private String apiV3Key;
    private String platformCertificatePath;

    public String getMchId() { return mchId; }
    public void setMchId(String mchId) { this.mchId = mchId; }
    public String getMerchantSerialNo() { return merchantSerialNo; }
    public void setMerchantSerialNo(String merchantSerialNo) { this.merchantSerialNo = merchantSerialNo; }
    public String getPrivateKeyPath() { return privateKeyPath; }
    public void setPrivateKeyPath(String privateKeyPath) { this.privateKeyPath = privateKeyPath; }
    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
    public String getRefundNotifyUrl() { return refundNotifyUrl; }
    public void setRefundNotifyUrl(String refundNotifyUrl) { this.refundNotifyUrl = refundNotifyUrl; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getApiV3Key() { return apiV3Key; }
    public void setApiV3Key(String apiV3Key) { this.apiV3Key = apiV3Key; }
    public String getPlatformCertificatePath() { return platformCertificatePath; }
    public void setPlatformCertificatePath(String platformCertificatePath) { this.platformCertificatePath = platformCertificatePath; }
}
