package com.ruoyi.web.domain.xy;

import java.math.BigDecimal;
import com.ruoyi.common.annotation.Excel;

/** 财务对账 Excel 导出行。 */
public class XyFinanceExportRow
{
    @Excel(name = "支付单号", width = 24)
    private String paymentNo;

    @Excel(name = "业务类型", width = 16)
    private String businessType;

    @Excel(name = "金额（元）", cellType = Excel.ColumnType.NUMERIC, scale = 2, isStatistics = true)
    private BigDecimal amount;

    @Excel(name = "支付渠道", width = 14)
    private String channel;

    @Excel(name = "支付状态", width = 14)
    private String status;

    @Excel(name = "微信交易单号", width = 28)
    private String transactionId;

    @Excel(name = "会员昵称", width = 18)
    private String nickname;

    @Excel(name = "手机号", width = 16)
    private String mobile;

    @Excel(name = "确认时间", width = 22)
    private String paidTime;

    @Excel(name = "创建时间", width = 22)
    private String createTime;

    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getPaidTime() { return paidTime; }
    public void setPaidTime(String paidTime) { this.paidTime = paidTime; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
