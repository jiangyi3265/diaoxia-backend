package com.ruoyi.web.controller.xy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.web.service.xy.XyBusinessService;
import com.ruoyi.web.service.xy.XyWechatService;
import com.ruoyi.web.service.xy.XyWechatPayService;

/** 小程序业务接口。身份由 X-App-Token 表示，不能与后台管理员 JWT 混用。 */
@RestController
@RequestMapping("/app")
public class XyAppController
{
    private static final Logger log = LoggerFactory.getLogger(XyAppController.class);
    private final XyBusinessService service;
    private final XyWechatService wechatService;
    private final XyWechatPayService wechatPayService;

    public XyAppController(XyBusinessService service, XyWechatService wechatService, XyWechatPayService wechatPayService)
    {
        this.service = service;
        this.wechatService = wechatService;
        this.wechatPayService = wechatPayService;
    }

    @Anonymous
    @PostMapping("/auth/session")
    public AjaxResult createSession(@RequestBody Map<String, Object> body)
    {
        Object codeValue = body.get("code");
        Object inviteCodeValue = body.get("inviteCode");
        String[] identity = wechatService.exchangeCode(codeValue == null ? null : String.valueOf(codeValue).trim());
        return AjaxResult.success(service.loginByOpenId(identity[0], identity[1],
                inviteCodeValue == null ? null : String.valueOf(inviteCodeValue).trim()));
    }

    @Anonymous
    @GetMapping("/stores")
    public AjaxResult stores() { return AjaxResult.success(service.listStores()); }

    @Anonymous
    @GetMapping("/membership-plans")
    public AjaxResult membershipPlans() { return AjaxResult.success(service.listMembershipPlans()); }

    @Anonymous
    @GetMapping("/products")
    public AjaxResult products(Long productId, @RequestHeader(value = "X-App-Token", required = false) String token)
    {
        return AjaxResult.success(service.listProducts(productId, service.optionalMember(token)));
    }
    @Anonymous
    @GetMapping("/notification-settings")
    public AjaxResult notificationSettings()
    {
        Map<String, Object> result = new HashMap<>();
        result.put("reservationReminderTemplateId", wechatService.getReservationReminderTemplateId());
        return AjaxResult.success(result);
    }

    @Anonymous
    @GetMapping("/payment-settings")
    public AjaxResult paymentSettings()
    {
        Map<String, Object> result = new HashMap<>();
        boolean demoEnabled = wechatPayService.isDemoEnabled();
        boolean wechatPayEnabled = !demoEnabled && wechatPayService.isWechatPayConfigured();
        result.put("demoEnabled", demoEnabled);
        result.put("wechatPayEnabled", wechatPayEnabled);
        result.put("offlinePaymentEnabled", !demoEnabled && !wechatPayEnabled);
        result.put("mode", demoEnabled ? "DEMO" : wechatPayEnabled ? "WECHAT" : "OFFLINE");
        if (!demoEnabled && !wechatPayEnabled)
            result.put("notice", "微信支付暂未开通，请到店付款后由工作人员确认");
        return AjaxResult.success(result);
    }

    @Anonymous
    @GetMapping("/stores/{storeId}/availability")
    public AjaxResult availability(@PathVariable Long storeId, String date)
    {
        return AjaxResult.success(service.reservationAvailability(storeId, parseDate(date)));
    }

    @Anonymous
    @GetMapping("/me")
    public AjaxResult me(@RequestHeader(value = "X-App-Token", required = false) String token)
    {
        return AjaxResult.success(service.memberProfile(service.requireMember(token)));
    }

    @Anonymous
    @PutMapping("/me")
    public AjaxResult updateProfile(@RequestHeader(value = "X-App-Token", required = false) String token, @RequestBody Map<String, Object> body)
    {
        service.updateMemberProfile(service.requireMember(token), body); return AjaxResult.success();
    }

    @Anonymous
    @PostMapping("/reservations")
    public AjaxResult book(@RequestHeader(value = "X-App-Token", required = false) String token, @RequestBody Map<String, Object> body)
    {
        Long memberId = service.requireMember(token);
        return AjaxResult.success(service.createReservation(memberId, requiredLong(body, "storeId"), requiredLong(body, "slotId"), requiredLong(body, "seatId"), parseDate(String.valueOf(body.get("date")))));
    }

    @Anonymous
    @GetMapping("/reservations")
    public AjaxResult reservations(@RequestHeader(value = "X-App-Token", required = false) String token)
    {
        return AjaxResult.success(service.memberReservations(service.requireMember(token)));
    }

    @Anonymous
    @GetMapping("/reservations/{reservationNo}")
    public AjaxResult reservation(@RequestHeader(value = "X-App-Token", required = false) String token, @PathVariable String reservationNo)
    {
        return AjaxResult.success(service.reservationDetail(service.requireMember(token), reservationNo));
    }

    @Anonymous
    @PostMapping("/reservations/{reservationNo}/cancel")
    public AjaxResult cancel(@RequestHeader(value = "X-App-Token", required = false) String token, @PathVariable String reservationNo)
    {
        service.cancelReservation(service.requireMember(token), reservationNo); return AjaxResult.success();
    }

    @Anonymous
    @GetMapping("/addresses")
    public AjaxResult addresses(@RequestHeader(value = "X-App-Token", required = false) String token) { return AjaxResult.success(service.addresses(service.requireMember(token))); }

    @Anonymous
    @PostMapping("/addresses")
    public AjaxResult createAddress(@RequestHeader(value = "X-App-Token", required = false) String token, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveAddress(service.requireMember(token), body)); }

    @Anonymous
    @PutMapping("/addresses")
    public AjaxResult updateAddress(@RequestHeader(value = "X-App-Token", required = false) String token, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveAddress(service.requireMember(token), body)); }

    @Anonymous
    @DeleteMapping("/addresses/{addressId}")
    public AjaxResult deleteAddress(@RequestHeader(value = "X-App-Token", required = false) String token, @PathVariable Long addressId) { service.deleteAddress(service.requireMember(token), addressId); return AjaxResult.success(); }

    @Anonymous
    @PostMapping("/orders")
    public AjaxResult createOrder(@RequestHeader(value = "X-App-Token", required = false) String token, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.createOrder(service.requireMember(token), body)); }

    @Anonymous
    @GetMapping("/orders")
    public AjaxResult orders(@RequestHeader(value = "X-App-Token", required = false) String token) { return AjaxResult.success(service.memberOrders(service.requireMember(token))); }

    @Anonymous
    @GetMapping("/orders/{orderNo}")
    public AjaxResult order(@RequestHeader(value = "X-App-Token", required = false) String token, @PathVariable String orderNo) { return AjaxResult.success(service.orderDetail(service.requireMember(token), orderNo)); }

    @Anonymous
    @GetMapping("/membership-code")
    public AjaxResult membershipCode(@RequestHeader(value="X-App-Token",required=false)String token){return AjaxResult.success(service.issueMemberVerifyCode(service.requireMember(token)));}

    @Anonymous
    @PostMapping("/membership-payments/{planId}")
    public AjaxResult membershipPayment(@RequestHeader(value="X-App-Token",required=false)String token,@PathVariable Long planId){return AjaxResult.success(service.createMembershipPayment(service.requireMember(token),planId,wechatPayService));}

    @Anonymous
    @GetMapping("/bills")
    public AjaxResult bills(@RequestHeader(value="X-App-Token",required=false)String token){return AjaxResult.success(service.memberBills(service.requireMember(token)));}

    @Anonymous
    @PostMapping("/payments/wechat/notify")
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map<String,String>> wechatNotify(@RequestHeader(value="Wechatpay-Timestamp",required=false) String timestamp,@RequestHeader(value="Wechatpay-Nonce",required=false) String nonce,@RequestHeader(value="Wechatpay-Signature",required=false) String signature,@RequestHeader(value="Wechatpay-Serial",required=false) String serial,@RequestBody(required=false) String body)
    {
        try
        {
            Map data = wechatPayService.verifyCallback(timestamp, nonce, signature, serial, body);
            wechatPayService.validateNotificationIdentity(data);
            if (!"SUCCESS".equals(data.get("trade_state"))) throw new ServiceException("微信支付未成功");
            Map amount = (Map) data.get("amount");
            if (amount == null || !(amount.get("total") instanceof Number)) throw new ServiceException("微信支付回调金额缺失");
            service.completeWechatPayment(String.valueOf(data.get("out_trade_no")), String.valueOf(data.get("transaction_id")), ((Number) amount.get("total")).intValue());
            return ResponseEntity.ok(callbackResult("SUCCESS", "成功"));
        }
        catch (RuntimeException ex)
        {
            // 失败必须返回非 2xx，微信平台才会重试；不在日志/响应中暴露回调原文。
            log.warn("微信支付回调处理失败，异常类型={}", ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(callbackResult("FAIL", "处理失败"));
        }
    }

    @Anonymous
    @PostMapping("/payments/wechat/refund-notify")
    @SuppressWarnings("rawtypes")
    public ResponseEntity<Map<String,String>> wechatRefundNotify(@RequestHeader(value="Wechatpay-Timestamp",required=false)String timestamp,@RequestHeader(value="Wechatpay-Nonce",required=false)String nonce,@RequestHeader(value="Wechatpay-Signature",required=false)String signature,@RequestHeader(value="Wechatpay-Serial",required=false)String serial,@RequestBody(required=false) String body)
    {
        try
        {
            Map data = wechatPayService.verifyCallback(timestamp, nonce, signature, serial, body);
            wechatPayService.validateNotificationIdentity(data);
            String refundNo = String.valueOf(data.get("out_refund_no"));
            String refundId = String.valueOf(data.get("refund_id"));
            if ("SUCCESS".equals(data.get("refund_status"))) service.completeRefund(refundNo, refundId);
            else service.failRefund(refundNo, refundId);
            return ResponseEntity.ok(callbackResult("SUCCESS", "成功"));
        }
        catch (RuntimeException ex)
        {
            log.warn("微信退款回调处理失败，异常类型={}", ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(callbackResult("FAIL", "处理失败"));
        }
    }

    @Anonymous
    @PostMapping("/orders/{orderNo}/payment")
    public AjaxResult orderPayment(@RequestHeader(value = "X-App-Token", required = false) String token, @PathVariable String orderNo) { return AjaxResult.success(service.createOrderPayment(service.requireMember(token),orderNo,wechatPayService)); }

    @Anonymous
    @PostMapping("/orders/{orderNo}/cancel")
    public AjaxResult cancelOrder(@RequestHeader(value="X-App-Token",required=false)String token,@PathVariable String orderNo){service.cancelOrder(service.requireMember(token),orderNo);return AjaxResult.success();}

    @Anonymous
    @PostMapping("/orders/{orderNo}/receipt")
    public AjaxResult receipt(@RequestHeader(value = "X-App-Token", required = false) String token, @PathVariable String orderNo) { service.confirmReceipt(service.requireMember(token), orderNo); return AjaxResult.success(); }

    @Anonymous
    @PostMapping("/orders/{orderNo}/after-sales")
    public AjaxResult afterSale(@RequestHeader(value = "X-App-Token", required = false) String token, @PathVariable String orderNo, @RequestBody Map<String, Object> body)
    {
        Object reasonValue = body.get("reason");
        String reason = reasonValue == null ? null : String.valueOf(reasonValue).trim();
        if (StringUtils.isEmpty(reason)) throw new ServiceException("请选择售后原因");
        return AjaxResult.success((Object) service.createAfterSale(service.requireMember(token), orderNo, reason, body.get("description") == null ? null : String.valueOf(body.get("description")).trim()));
    }

    private LocalDate parseDate(String text)
    {
        try { return LocalDate.parse(text); } catch (Exception ex) { throw new ServiceException("日期格式必须为 yyyy-MM-dd"); }
    }

    private Long requiredLong(Map<String, Object> body, String key)
    {
        try { return Long.valueOf(String.valueOf(body.get(key))); } catch (Exception ex) { throw new ServiceException(key + "不能为空且必须为数字"); }
    }

    private Map<String,String> callbackResult(String code, String message)
    {
        Map<String,String> result = new HashMap<>();
        result.put("code", code);
        result.put("message", message);
        return result;
    }
}
