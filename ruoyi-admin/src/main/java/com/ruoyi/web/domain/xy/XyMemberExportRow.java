package com.ruoyi.web.domain.xy;

import com.ruoyi.common.annotation.Excel;

/** 会员名单 Excel 导出行。 */
public class XyMemberExportRow
{
    @Excel(name = "会员ID", width = 14, cellType = Excel.ColumnType.TEXT)
    private String memberId;

    @Excel(name = "会员昵称", width = 18)
    private String nickname;

    @Excel(name = "手机号", width = 16, cellType = Excel.ColumnType.TEXT)
    private String mobile;

    @Excel(name = "邀请码", width = 14, cellType = Excel.ColumnType.TEXT)
    private String inviteCode;

    @Excel(name = "会员卡号", width = 24, cellType = Excel.ColumnType.TEXT)
    private String cardNo;

    @Excel(name = "生效日期", width = 14)
    private String startDate;

    @Excel(name = "到期日期", width = 14)
    private String expireDate;

    @Excel(name = "会员卡状态", width = 14)
    private String cardStatus;

    @Excel(name = "账号状态", width = 12)
    private String memberStatus;

    @Excel(name = "邀请人昵称", width = 18)
    private String inviterNickname;

    @Excel(name = "邀请人邀请码", width = 16, cellType = Excel.ColumnType.TEXT)
    private String inviterInviteCode;

    @Excel(name = "注册时间", width = 22)
    private String createTime;

    public String getMemberId() { return memberId; }
    public void setMemberId(String memberId) { this.memberId = memberId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getInviteCode() { return inviteCode; }
    public void setInviteCode(String inviteCode) { this.inviteCode = inviteCode; }
    public String getCardNo() { return cardNo; }
    public void setCardNo(String cardNo) { this.cardNo = cardNo; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getExpireDate() { return expireDate; }
    public void setExpireDate(String expireDate) { this.expireDate = expireDate; }
    public String getCardStatus() { return cardStatus; }
    public void setCardStatus(String cardStatus) { this.cardStatus = cardStatus; }
    public String getMemberStatus() { return memberStatus; }
    public void setMemberStatus(String memberStatus) { this.memberStatus = memberStatus; }
    public String getInviterNickname() { return inviterNickname; }
    public void setInviterNickname(String inviterNickname) { this.inviterNickname = inviterNickname; }
    public String getInviterInviteCode() { return inviterInviteCode; }
    public void setInviterInviteCode(String inviterInviteCode) { this.inviterInviteCode = inviterInviteCode; }
    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
