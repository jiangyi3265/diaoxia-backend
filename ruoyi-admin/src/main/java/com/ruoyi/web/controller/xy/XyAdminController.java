package com.ruoyi.web.controller.xy;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.web.service.xy.XyBusinessService;
import com.ruoyi.web.service.xy.XyWechatPayService;
import com.ruoyi.common.utils.SecurityUtils;

/** 后台运营接口：完全使用若依管理员登录态和权限体系。 */
@RestController
@RequestMapping("/xy")
public class XyAdminController
{
    private final XyBusinessService service;
    private final XyWechatPayService wechatPayService;
    public XyAdminController(XyBusinessService service,XyWechatPayService wechatPayService) { this.service = service; this.wechatPayService=wechatPayService; }

    @PreAuthorize("@ss.hasPermi('xy:dashboard:view')")
    @GetMapping("/dashboard") public AjaxResult dashboard() { return AjaxResult.success(service.dashboard()); }

    @PreAuthorize("@ss.hasPermi('xy:member:list')")
    @GetMapping("/members") public AjaxResult members(@RequestParam(required = false) String keyword) { return AjaxResult.success(service.adminMembers(keyword)); }

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
    @PostMapping("/seats") public AjaxResult createSeat(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveSeat(body)); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:config')")
    @PutMapping("/seats") public AjaxResult updateSeat(@RequestBody Map<String, Object> body) { return AjaxResult.success(service.saveSeat(body)); }

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

    @PreAuthorize("@ss.hasPermi('xy:product:list')") @GetMapping("/orders") public AjaxResult orders(){return AjaxResult.success(service.adminOrders());}
    @PreAuthorize("@ss.hasPermi('xy:product:edit')") @PostMapping("/orders/{orderNo}/ship") public AjaxResult ship(@PathVariable String orderNo){service.shipOrder(orderNo);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('xy:product:list')") @GetMapping("/after-sales") public AjaxResult afterSales(){return AjaxResult.success(service.adminAfterSales());}
    @PreAuthorize("@ss.hasPermi('xy:product:edit')") @PostMapping("/after-sales/{afterSaleNo}/approve") public AjaxResult approveAfterSale(@PathVariable String afterSaleNo){service.approveAfterSale(afterSaleNo,wechatPayService);return AjaxResult.success();}
    @PreAuthorize("@ss.hasPermi('xy:product:edit')") @PostMapping("/after-sales/{afterSaleNo}/reject") public AjaxResult rejectAfterSale(@PathVariable String afterSaleNo){service.rejectAfterSale(afterSaleNo);return AjaxResult.success();}

    @PreAuthorize("@ss.hasPermi('xy:staff:list')")
    @GetMapping("/staff") public AjaxResult staff() { return AjaxResult.success(service.staffMembers()); }

    @PreAuthorize("@ss.hasPermi('xy:reservation:verify')")
    @GetMapping("/verification-records") public AjaxResult verificationRecords() { return AjaxResult.success(service.verificationRecords()); }

    private LocalDate parseDate(String text)
    {
        try { return LocalDate.parse(text); } catch (Exception ex) { throw new ServiceException("日期格式必须为 yyyy-MM-dd"); }
    }
}
