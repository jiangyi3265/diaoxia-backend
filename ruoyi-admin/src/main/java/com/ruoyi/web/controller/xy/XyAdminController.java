package com.ruoyi.web.controller.xy;

import java.time.LocalDate;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.web.domain.xy.XyFinanceExportRow;
import com.ruoyi.web.service.xy.XyBusinessService;
import com.ruoyi.web.service.xy.XyWechatPayService;
import com.ruoyi.web.service.xy.XyBenefitEventService;
import com.ruoyi.common.utils.SecurityUtils;

/** 后台运营接口：完全使用若依管理员登录态和权限体系。 */
@RestController
@RequestMapping("/xy")
public class XyAdminController
{
    private final XyBusinessService service;
    private final XyWechatPayService wechatPayService;
    private final XyBenefitEventService benefitEventService;
    public XyAdminController(XyBusinessService service,XyWechatPayService wechatPayService,
            XyBenefitEventService benefitEventService)
    {
        this.service = service;
        this.wechatPayService=wechatPayService;
        this.benefitEventService=benefitEventService;
    }

    @PreAuthorize("@ss.hasPermi('xy:dashboard:view')")
    @GetMapping("/dashboard") public AjaxResult dashboard() { return AjaxResult.success(service.dashboard()); }

    @PreAuthorize("@ss.hasPermi('xy:member:list')")
    @GetMapping("/members") public AjaxResult members(@RequestParam(required = false) String keyword) { return AjaxResult.success(service.adminMembers(keyword)); }

    @PreAuthorize("@ss.hasPermi('xy:member:list')")
    @GetMapping("/members/plans") public AjaxResult membershipPlans() { return AjaxResult.success(service.adminMembershipPlans()); }

    @Log(title = "会员管理", businessType = BusinessType.INSERT)
    @PreAuthorize("@ss.hasPermi('xy:member:edit')")
    @PostMapping("/members") public AjaxResult createMember(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveAdminMember(null, body)); }

    @Log(title = "会员管理", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:member:edit')")
    @PutMapping("/members/{memberId}") public AjaxResult updateMember(@PathVariable Long memberId, @RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveAdminMember(memberId, body)); }

    @Log(title = "会员管理", businessType = BusinessType.DELETE)
    @PreAuthorize("@ss.hasPermi('xy:member:edit')")
    @DeleteMapping("/members/{memberId}") public AjaxResult deleteMember(@PathVariable Long memberId) { service.deleteAdminMember(memberId); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:list')")
    @GetMapping("/reservations") public AjaxResult reservations(@RequestParam String date, @RequestParam(required = false) String status) { return AjaxResult.success(service.adminReservations(parseDate(date), status)); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:verify')")
    @PostMapping("/reservations/verify/{verifyCode}") public AjaxResult verify(@PathVariable String verifyCode) { service.checkIn(verifyCode); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:verify')")
    @PostMapping("/members/verify/{verifyCode}") public AjaxResult verifyMember(@PathVariable String verifyCode) { return AjaxResult.success(service.verifyMemberCode(verifyCode,SecurityUtils.getUsername())); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @GetMapping("/reservation-configuration") public AjaxResult reservationConfiguration() { return AjaxResult.success(service.reservationConfiguration()); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PostMapping("/stores") public AjaxResult createStore(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveStore(body)); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PutMapping("/stores") public AjaxResult updateStore(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveStore(body)); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PostMapping("/reservation-slots") public AjaxResult createSlot(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveSlot(body)); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PutMapping("/reservation-slots") public AjaxResult updateSlot(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveSlot(body)); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @GetMapping("/reservation-pauses") public AjaxResult reservationPauses() { return AjaxResult.success(service.adminReservationPauses()); }

    @Log(title = "暂停预约", businessType = BusinessType.INSERT)
    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PostMapping("/reservation-pauses") public AjaxResult createReservationPause(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveReservationPause(body, SecurityUtils.getUsername())); }

    @Log(title = "恢复预约", businessType = BusinessType.DELETE)
    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @DeleteMapping("/reservation-pauses/{batchNo}") public AjaxResult resumeReservationPause(@PathVariable String batchNo) { service.resumeReservationPause(batchNo); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PostMapping("/seats") public AjaxResult createSeat(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveSeat(body)); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PutMapping("/seats") public AjaxResult updateSeat(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveSeat(body)); }

    @PreAuthorize("@ss.hasPermi('xy:benefit:list')")
    @GetMapping("/benefit-events") public AjaxResult benefitEvents() { return AjaxResult.success(benefitEventService.adminEvents()); }

    @PreAuthorize("@ss.hasPermi('xy:benefit:list')")
    @GetMapping("/benefit-events/{eventId}") public AjaxResult benefitEvent(@PathVariable Long eventId) { return AjaxResult.success(benefitEventService.adminEvent(eventId)); }

    @Log(title = "福利钓专场", businessType = BusinessType.INSERT)
    @PreAuthorize("@ss.hasPermi('xy:benefit:edit')")
    @PostMapping("/benefit-events") public AjaxResult createBenefitEvent(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(benefitEventService.saveEvent(null, body, SecurityUtils.getUsername()));
    }

    @Log(title = "福利钓专场", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:benefit:edit')")
    @PutMapping("/benefit-events/{eventId}") public AjaxResult updateBenefitEvent(@PathVariable Long eventId,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(benefitEventService.saveEvent(eventId, body, SecurityUtils.getUsername()));
    }

    @Log(title = "删除福利钓专场", businessType = BusinessType.DELETE)
    @PreAuthorize("@ss.hasPermi('xy:benefit:edit')")
    @DeleteMapping("/benefit-events/{eventId}") public AjaxResult deleteBenefitEvent(@PathVariable Long eventId)
    {
        return AjaxResult.success(benefitEventService.deleteEvent(eventId, SecurityUtils.getUsername()));
    }

    @Log(title = "确认福利钓开始", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:benefit:edit')")
    @PostMapping("/benefit-events/{eventId}/confirm") public AjaxResult confirmBenefitEvent(@PathVariable Long eventId)
    {
        return AjaxResult.success(benefitEventService.confirmEvent(eventId, SecurityUtils.getUsername()));
    }

    @Log(title = "取消福利钓专场", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:benefit:refund')")
    @PostMapping("/benefit-events/{eventId}/cancel") public AjaxResult cancelBenefitEvent(@PathVariable Long eventId,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(benefitEventService.cancelEvent(eventId,
                body.get("reason") == null ? null : String.valueOf(body.get("reason")), SecurityUtils.getUsername()));
    }

    @Log(title = "福利钓单座资金处理", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:benefit:refund')")
    @PostMapping("/benefit-bookings/{bookingId}/refund") public AjaxResult refundBenefitBooking(@PathVariable Long bookingId,
            @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(benefitEventService.refundBooking(bookingId,
                body.get("reason") == null ? null : String.valueOf(body.get("reason")), SecurityUtils.getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('xy:member:plan')")
    @PostMapping("/membership-plans") public AjaxResult createPlan(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.savePlan(body)); }

    @PreAuthorize("@ss.hasPermi('xy:member:plan')")
    @PutMapping("/membership-plans") public AjaxResult updatePlan(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.savePlan(body)); }

    @PreAuthorize("@ss.hasPermi('xy:product:list')")
    @GetMapping("/products") public AjaxResult products() { return AjaxResult.success(service.adminProducts()); }
    @PreAuthorize("@ss.hasPermi('xy:product:list')")
    @GetMapping("/member-discount-settings") public AjaxResult memberDiscountSettings() { return AjaxResult.success(service.memberDiscountSettings()); }
    @PreAuthorize("@ss.hasPermi('xy:product:edit')")
    @PutMapping("/member-discount-settings") public AjaxResult updateMemberDiscountSettings(@RequestBody Map<String, Object> body) { service.saveMemberDiscountSettings(body); return AjaxResult.success(); }

    @PreAuthorize("@ss.hasPermi('xy:product:edit')")
    @PostMapping("/products") public AjaxResult createProduct(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveProduct(body)); }

    @PreAuthorize("@ss.hasPermi('xy:product:edit')")
    @PutMapping("/products") public AjaxResult updateProduct(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveProduct(body)); }

    @PreAuthorize("@ss.hasPermi('xy:finance:view')")
    @GetMapping("/finance") public AjaxResult finance() { return AjaxResult.success(service.financeRecords()); }

    @Log(title = "财务对账", businessType = BusinessType.EXPORT)
    @PreAuthorize("@ss.hasPermi('xy:finance:view')")
    @PostMapping("/finance/export")
    public void exportFinance(HttpServletResponse response)
    {
        new ExcelUtil<XyFinanceExportRow>(XyFinanceExportRow.class)
                .exportExcel(response, service.financeExportRows(), "财务对账");
    }

    @PreAuthorize("@ss.hasPermi('xy:finance:view')")
    @GetMapping("/offline-payments") public AjaxResult offlinePayments(@RequestParam(required = false) String status) { return AjaxResult.success(service.offlinePayments(status)); }

    @Log(title = "线下收款确认", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:finance:collect')")
    @PostMapping("/offline-payments/{paymentNo}/confirm") public AjaxResult confirmOfflinePayment(@PathVariable String paymentNo) { return AjaxResult.success(service.confirmOfflinePayment(paymentNo, SecurityUtils.getUsername())); }

    @Log(title = "线下收款关闭", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:finance:collect')")
    @PostMapping("/offline-payments/{paymentNo}/close") public AjaxResult closeOfflinePayment(@PathVariable String paymentNo) { return AjaxResult.success(service.closeOfflinePayment(paymentNo, SecurityUtils.getUsername())); }

    @PreAuthorize("@ss.hasPermi('xy:product:list')") @GetMapping("/orders") public AjaxResult orders(){return AjaxResult.success(service.adminOrders());}
    @PreAuthorize("@ss.hasPermi('xy:product:edit')") @PostMapping("/orders/{orderNo}/ship") public AjaxResult ship(@PathVariable String orderNo){service.shipOrder(orderNo);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('xy:product:list')") @GetMapping("/after-sales") public AjaxResult afterSales(){return AjaxResult.success(service.adminAfterSales());}
    @PreAuthorize("@ss.hasPermi('xy:product:edit')") @PostMapping("/after-sales/{afterSaleNo}/approve") public AjaxResult approveAfterSale(@PathVariable String afterSaleNo){service.approveAfterSale(afterSaleNo,wechatPayService);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('xy:product:edit')") @PostMapping("/after-sales/{afterSaleNo}/reject") public AjaxResult rejectAfterSale(@PathVariable String afterSaleNo){service.rejectAfterSale(afterSaleNo);return AjaxResult.success();}
    @Log(title = "线下退款确认", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:finance:collect')") @PostMapping("/after-sales/{afterSaleNo}/complete-offline-refund") public AjaxResult completeOfflineRefund(@PathVariable String afterSaleNo){service.completeOfflineRefund(afterSaleNo);return AjaxResult.success();}
    @Log(title = "退货商品回库", businessType = BusinessType.UPDATE)
    @PreAuthorize("@ss.hasPermi('xy:product:edit')") @PostMapping("/after-sales/{afterSaleNo}/restock") public AjaxResult restockReturnedAfterSale(@PathVariable String afterSaleNo){service.restockReturnedAfterSale(afterSaleNo);return AjaxResult.success();}

    @PreAuthorize("@ss.hasPermi('xy:staff:list')")
    @GetMapping("/staff") public AjaxResult staff() { return AjaxResult.success(service.staffMembers()); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:verify')")
    @GetMapping("/verification-records") public AjaxResult verificationRecords() { return AjaxResult.success(service.verificationRecords()); }

    private LocalDate parseDate(String text)
    {
        try { return LocalDate.parse(text); } catch (Exception ex) { throw new ServiceException("日期格式必须为 yyyy-MM-dd"); }
    }
}
