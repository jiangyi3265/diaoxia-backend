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
    private String subscribeReservationTemplateId;
    private String subscribeReservationPage = "pages/reserve/history";
    private String subscribeReservationTitleField = "thing1";
    private String subscribeReservationTimeField = "time2";
    private String subscribeBenefitStartTemplateId;
    private String subscribeBenefitCancelTemplateId;
    private String subscribeBenefitPage = "pages/benefit/history";
    private String subscribeBenefitTitleField = "thing1";
    private String subscribeBenefitTimeField = "time2";
    private String subscribeBenefitNoteField = "thing3";

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
    public String getSubscribeReservationTemplateId() { return subscribeReservationTemplateId; }
    public void setSubscribeReservationTemplateId(String subscribeReservationTemplateId) { this.subscribeReservationTemplateId = subscribeReservationTemplateId; }
    public String getSubscribeReservationPage() { return subscribeReservationPage; }
    public void setSubscribeReservationPage(String subscribeReservationPage) { this.subscribeReservationPage = subscribeReservationPage; }
    public String getSubscribeReservationTitleField() { return subscribeReservationTitleField; }
    public void setSubscribeReservationTitleField(String subscribeReservationTitleField) { this.subscribeReservationTitleField = subscribeReservationTitleField; }
    public String getSubscribeReservationTimeField() { return subscribeReservationTimeField; }
    public void setSubscribeReservationTimeField(String subscribeReservationTimeField) { this.subscribeReservationTimeField = subscribeReservationTimeField; }
    public String getSubscribeBenefitStartTemplateId() { return subscribeBenefitStartTemplateId; }
    public void setSubscribeBenefitStartTemplateId(String subscribeBenefitStartTemplateId) { this.subscribeBenefitStartTemplateId = subscribeBenefitStartTemplateId; }
    public String getSubscribeBenefitCancelTemplateId() { return subscribeBenefitCancelTemplateId; }
    public void setSubscribeBenefitCancelTemplateId(String subscribeBenefitCancelTemplateId) { this.subscribeBenefitCancelTemplateId = subscribeBenefitCancelTemplateId; }
    public String getSubscribeBenefitPage() { return subscribeBenefitPage; }
    public void setSubscribeBenefitPage(String subscribeBenefitPage) { this.subscribeBenefitPage = subscribeBenefitPage; }
    public String getSubscribeBenefitTitleField() { return subscribeBenefitTitleField; }
    public void setSubscribeBenefitTitleField(String subscribeBenefitTitleField) { this.subscribeBenefitTitleField = subscribeBenefitTitleField; }
    public String getSubscribeBenefitTimeField() { return subscribeBenefitTimeField; }
    public void setSubscribeBenefitTimeField(String subscribeBenefitTimeField) { this.subscribeBenefitTimeField = subscribeBenefitTimeField; }
    public String getSubscribeBenefitNoteField() { return subscribeBenefitNoteField; }
    public void setSubscribeBenefitNoteField(String subscribeBenefitNoteField) { this.subscribeBenefitNoteField = subscribeBenefitNoteField; }
}
