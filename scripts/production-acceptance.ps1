param(
    [string]$BaseUrl = "https://dx.oksja.cn",
    [string]$SshHost = "root@101.42.94.41",
    [string]$SshHostKey = "ssh-ed25519 255 SHA256:KrF5l8Qt7ESXbQSNAOiNmSj7iDAgMeSM42BY+/cIylU",
    [string]$PlinkPath = "C:\Program Files\PuTTY\plink.exe"
)

$ErrorActionPreference = "Stop"
$sshPassword = $env:DX_SSH_PASSWORD
$adminPassword = $env:DX_ADMIN_PASSWORD
if ([string]::IsNullOrWhiteSpace($sshPassword) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
    throw "Set DX_SSH_PASSWORD and DX_ADMIN_PASSWORD before running this test."
}

$suffix = Get-Date -Format "MMddHHmmss"
$openid = "codex_e2e_$suffix"
$appToken = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
$adminLoginKey = ""
$passed = [System.Collections.Generic.List[string]]::new()
$limited = [System.Collections.Generic.List[string]]::new()

function Invoke-Remote([string]$script) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($script))
    $output = & $PlinkPath -batch -ssh -hostkey $SshHostKey -pw $sshPassword $SshHost "echo $encoded | base64 -d | bash" 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed" }
    return (($output) -join "`n").Trim()
}

function Invoke-Business(
    [string]$name,
    [string]$path,
    [string]$method = "GET",
    $body = $null,
    $headers = $null
) {
    $request = @{ Uri = "$BaseUrl$path"; Method = $method; TimeoutSec = 20 }
    if ($headers) { $request.Headers = $headers }
    if ($null -ne $body) {
        $request.Body = $body | ConvertTo-Json -Depth 8 -Compress
        $request.ContentType = "application/json"
    }
    $response = Invoke-RestMethod @request
    if ([int]$response.code -ne 200) { throw "$name failed: [$($response.code)] $($response.msg)" }
    $passed.Add($name)
    return $response.data
}

try {
    Invoke-Remote "redis-cli SET 'sys_config:sys.account.captchaEnabled' '`"false`"' >/dev/null" | Out-Null
    $login = Invoke-RestMethod "$BaseUrl/login" -Method POST -ContentType "application/json" -Body (@{
        username = "admin"; password = $adminPassword; code = ""; uuid = ""
    } | ConvertTo-Json -Compress)
    if ([int]$login.code -ne 200) { throw "Administrator login failed" }
    $passed.Add("后台管理员登录")
    $adminHeaders = @{ Authorization = "Bearer $($login.token)" }

    $jwtPayload = $login.token.Split('.')[1].Replace('-', '+').Replace('_', '/')
    while ($jwtPayload.Length % 4) { $jwtPayload += '=' }
    $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($jwtPayload)) | ConvertFrom-Json
    $adminLoginKey = "login_tokens:$($claims.login_user_key)"
    Invoke-Remote "redis-cli SET 'sys_config:sys.account.captchaEnabled' '`"true`"' >/dev/null" | Out-Null

    Invoke-Business "后台身份权限" "/getInfo" "GET" $null $adminHeaders | Out-Null
    Invoke-Business "后台动态菜单" "/getRouters" "GET" $null $adminHeaders | Out-Null

    $serverClock = (Invoke-Remote "printf '%s|%s|%s|%s|%s' `$(date +%F) `$(date -d '+8 minutes' +%H:%M) `$(date -d '+38 minutes' +%H:%M) `$(date -d '+45 minutes' +%H:%M) `$(date -d '+75 minutes' +%H:%M)").Split('|')
    $date, $start1, $end1, $start2, $end2 = $serverClock

    $storeId = [long](Invoke-Business "后台门店新增" "/xy/stores" "POST" @{
        storeName = "E2E门店-$suffix"; address = "测试地址"; phone = "13800000000";
        businessHours = "09:00-22:00"; status = "0"
    } $adminHeaders)
    Invoke-Business "后台门店修改" "/xy/stores" "PUT" @{
        storeId = $storeId; storeName = "E2E门店-$suffix-改"; address = "测试地址";
        phone = "13800000000"; businessHours = "09:00-23:00"; status = "0"
    } $adminHeaders | Out-Null

    $slot1 = [long](Invoke-Business "后台时段新增1" "/xy/reservation-slots" "POST" @{ storeId=$storeId; startTime=$start1; endTime=$end1; status="0"; sortOrder=1 } $adminHeaders)
    $slot2 = [long](Invoke-Business "后台时段新增2" "/xy/reservation-slots" "POST" @{ storeId=$storeId; startTime=$start2; endTime=$end2; status="0"; sortOrder=2 } $adminHeaders)
    Invoke-Business "后台时段修改" "/xy/reservation-slots" "PUT" @{ slotId=$slot2; storeId=$storeId; startTime=$start2; endTime=$end2; status="0"; sortOrder=3 } $adminHeaders | Out-Null
    $seat1 = [long](Invoke-Business "后台座位新增1" "/xy/seats" "POST" @{ storeId=$storeId; seatCode="E2E-A"; zoneName="测试区"; status="0"; sortOrder=1 } $adminHeaders)
    $seat2 = [long](Invoke-Business "后台座位新增2" "/xy/seats" "POST" @{ storeId=$storeId; seatCode="E2E-B"; zoneName="测试区"; status="0"; sortOrder=2 } $adminHeaders)
    Invoke-Business "后台座位修改" "/xy/seats" "PUT" @{ seatId=$seat2; storeId=$storeId; seatCode="E2E-B"; zoneName="续约区"; status="0"; sortOrder=2 } $adminHeaders | Out-Null

    $planId = [long](Invoke-Business "后台会员方案新增" "/xy/membership-plans" "POST" @{ planName="E2E会员-$suffix"; amount=99; durationDays=30; dailyReservationLimit=1; status="0" } $adminHeaders)
    Invoke-Business "后台会员方案修改" "/xy/membership-plans" "PUT" @{ planId=$planId; planName="E2E会员-$suffix"; amount=99; durationDays=30; dailyReservationLimit=1; status="0" } $adminHeaders | Out-Null
    $discountProduct = [long](Invoke-Business "后台折扣商品新增" "/xy/products" "POST" @{ productName="E2E折扣-$suffix"; categoryName="测试"; salePrice=100; memberDiscountEnabled=$true; stock=20; status="0" } $adminHeaders)
    $fullPriceProduct = [long](Invoke-Business "后台原价商品新增" "/xy/products" "POST" @{ productName="E2E原价-$suffix"; categoryName="测试"; salePrice=100; memberDiscountEnabled=$false; stock=20; status="0" } $adminHeaders)
    Invoke-Business "后台商品优惠开关修改" "/xy/products" "PUT" @{ productId=$fullPriceProduct; productName="E2E原价-$suffix"; categoryName="测试"; salePrice=100; memberDiscountEnabled=$false; stock=20; status="0" } $adminHeaders | Out-Null

    Invoke-Remote @"
set -e
set -a; . /etc/dxjava.env; set +a
export MYSQL_PWD="`$DB_PASSWORD"
member_id=`$(mysql -u"`$DB_USERNAME" diaoxia -Nse "insert into xy_member(openid,nickname,mobile,invite_code) values('$openid','E2E','13800000000','T$($suffix.Substring(5))'); select last_insert_id();" | tail -1)
mysql -u"`$DB_USERNAME" diaoxia -e "insert into xy_membership_card(member_id,plan_id,card_no,start_date,expire_date,status) values(`$member_id,$planId,'E2E$suffix',curdate(),date_add(curdate(),interval 30 day),'ACTIVE');"
redis-cli SET 'xy:member:token:$appToken' "`${member_id}L" EX 3600 >/dev/null
"@ | Out-Null
    $memberHeaders = @{ "X-App-Token" = $appToken }

    $me = Invoke-Business "小程序登录及会员卡" "/app/me" "GET" $null $memberHeaders
    if (!$me.card) { throw "Active membership card missing" }
    Invoke-Business "会员资料修改" "/app/me" "PUT" @{ nickname="E2E改"; mobile="13900000000" } $memberHeaders | Out-Null

    $discount = Invoke-Business "会员折扣配置读取" "/xy/member-discount-settings" "GET" $null $adminHeaders
    $products = Invoke-Business "商城会员价读取" "/app/products" "GET" $null $memberHeaders
    $eligible = $products | Where-Object productId -eq $discountProduct
    $excluded = $products | Where-Object productId -eq $fullPriceProduct
    if ([double]$eligible.memberPrice -ne [math]::Round(100 * [double]$discount.discountRate, 2) -or [double]$excluded.memberPrice -ne 100) { throw "Member discount mismatch" }
    $passed.Add("会员折扣及商品排除计算")

    Invoke-Business "门店时段座位可用性" "/app/stores/$storeId/availability?date=$date" | Out-Null
    $first = Invoke-Business "当天首次预约" "/app/reservations" "POST" @{ storeId=$storeId; slotId=$slot1; seatId=$seat1; date=$date } $memberHeaders
    Invoke-Business "预约详情" "/app/reservations/$($first.reservationNo)" "GET" $null $memberHeaders | Out-Null
    Invoke-Business "后台预约签到" "/xy/reservations/verify/$($first.verifyCode)" "POST" @{} $adminHeaders | Out-Null
    Invoke-Remote @"
set -a; . /etc/dxjava.env; set +a; export MYSQL_PWD="`$DB_PASSWORD"
mysql -u"`$DB_USERNAME" diaoxia -e "update xy_reservation_slot set start_time=time(date_sub(now(),interval 20 minute)),end_time=time(date_add(now(),interval 5 minute)) where slot_id=$slot1;"
"@ | Out-Null
    $second = Invoke-Business "签到后结束前10分钟续约" "/app/reservations" "POST" @{ storeId=$storeId; slotId=$slot2; seatId=$seat2; date=$date } $memberHeaders
    Invoke-Business "预约取消及座位释放" "/app/reservations/$($second.reservationNo)/cancel" "POST" @{} $memberHeaders | Out-Null
    Invoke-Business "预约列表" "/app/reservations" "GET" $null $memberHeaders | Out-Null

    $addressId = [long](Invoke-Business "地址新增" "/app/addresses" "POST" @{ receiverName="测试"; receiverMobile="13800000000"; province="广东"; city="深圳"; district="南山"; detail="1号"; isDefault=$true } $memberHeaders)
    Invoke-Business "地址修改" "/app/addresses" "PUT" @{ addressId=$addressId; receiverName="测试"; receiverMobile="13800000000"; province="广东"; city="深圳"; district="南山"; detail="2号"; isDefault=$true } $memberHeaders | Out-Null
    Invoke-Business "地址列表" "/app/addresses" "GET" $null $memberHeaders | Out-Null

    $order1 = Invoke-Business "会员折扣配送下单" "/app/orders" "POST" @{ productId=$discountProduct; quantity=2; addressId=$addressId; deliveryType="DELIVERY" } $memberHeaders
    if ([double]$order1.discountAmount -le 0) { throw "Order discount missing" }
    Invoke-Business "订单详情" "/app/orders/$($order1.orderNo)" "GET" $null $memberHeaders | Out-Null
    Invoke-Business "订单取消及库存回补" "/app/orders/$($order1.orderNo)/cancel" "POST" @{} $memberHeaders | Out-Null
    $order2 = Invoke-Business "排除优惠商品下单" "/app/orders" "POST" @{ productId=$fullPriceProduct; quantity=1; deliveryType="PICKUP" } $memberHeaders
    if ([double]$order2.discountAmount -ne 0) { throw "Excluded product was discounted" }
    $payment = Invoke-RestMethod "$BaseUrl/app/orders/$($order2.orderNo)/payment" -Method POST -Headers $memberHeaders -ContentType "application/json" -Body "{}"
    if ([int]$payment.code -eq 200) { throw "Payment unexpectedly succeeded without merchant config" }
    $limited.Add("微信支付实扣：商户参数未配置")
    Invoke-Business "原价订单取消" "/app/orders/$($order2.orderNo)/cancel" "POST" @{} $memberHeaders | Out-Null
    Invoke-Business "订单列表" "/app/orders" "GET" $null $memberHeaders | Out-Null

    $memberCode = Invoke-Business "动态会员码生成" "/app/membership-code" "GET" $null $memberHeaders
    Invoke-Business "后台会员码核销" "/xy/members/verify/$($memberCode.code)" "POST" @{} $adminHeaders | Out-Null
    $replay = Invoke-RestMethod "$BaseUrl/xy/members/verify/$($memberCode.code)" -Method POST -Headers $adminHeaders -ContentType "application/json" -Body "{}"
    if ([int]$replay.code -eq 200) { throw "Member code replay succeeded" }
    $passed.Add("会员码防重复核销")
    Invoke-Business "账单列表" "/app/bills" "GET" $null $memberHeaders | Out-Null

    $adminReads = @(
        @("后台看板", "/xy/dashboard"), @("后台会员", "/xy/members"),
        @("后台预约", "/xy/reservations?date=$date"), @("后台预约配置", "/xy/reservation-configuration"),
        @("后台商品", "/xy/products"), @("后台订单", "/xy/orders"), @("后台售后", "/xy/after-sales"),
        @("后台财务", "/xy/finance"), @("后台员工", "/xy/staff"), @("后台核销记录", "/xy/verification-records")
    )
    foreach ($read in $adminReads) { Invoke-Business $read[0] $read[1] "GET" $null $adminHeaders | Out-Null }
    Invoke-Business "地址删除" "/app/addresses/$addressId" "DELETE" $null $memberHeaders | Out-Null

    $notification = Invoke-Business "提醒配置" "/app/notification-settings"
    if (!$notification.reservationReminderTemplateId) { $limited.Add("预约前1天/2小时微信实推：模板ID未配置") }
    $unauthorized = Invoke-RestMethod "$BaseUrl/app/me"
    if ([int]$unauthorized.code -ne 401) { throw "Unauthenticated guard mismatch" }
    $passed.Add("未登录访问保护")

    "PASSED=$($passed.Count)"
    $passed | ForEach-Object { "PASS $_" }
    $limited | ForEach-Object { "LIMIT $_" }
}
finally {
    Invoke-Remote @"
set +e
set -a; . /etc/dxjava.env; set +a; export MYSQL_PWD="`$DB_PASSWORD"
redis-cli SET 'sys_config:sys.account.captchaEnabled' '"true"' >/dev/null
redis-cli DEL 'xy:member:token:$appToken' '$adminLoginKey' >/dev/null
mysql -u"`$DB_USERNAME" diaoxia <<'SQL'
SET FOREIGN_KEY_CHECKS=0;
delete from xy_reservation_notification_record where reservation_id in(select reservation_id from xy_reservation where member_id in(select member_id from xy_member where openid='$openid'));
delete from xy_payment where member_id in(select member_id from xy_member where openid='$openid');
delete from xy_order_item where order_id in(select order_id from xy_order where member_id in(select member_id from xy_member where openid='$openid'));
delete from xy_order where member_id in(select member_id from xy_member where openid='$openid');
delete from xy_address where member_id in(select member_id from xy_member where openid='$openid');
delete from xy_member_visit where member_id in(select member_id from xy_member where openid='$openid');
delete from xy_reservation where member_id in(select member_id from xy_member where openid='$openid');
delete from xy_membership_card where member_id in(select member_id from xy_member where openid='$openid');
delete from xy_member where openid='$openid';
delete from xy_product where product_name like 'E2E%-$suffix';
delete from xy_seat where store_id in(select store_id from xy_store where store_name like 'E2E门店-$suffix%');
delete from xy_reservation_slot where store_id in(select store_id from xy_store where store_name like 'E2E门店-$suffix%');
delete from xy_store where store_name like 'E2E门店-$suffix%';
delete from xy_membership_plan where plan_name='E2E会员-$suffix';
SET FOREIGN_KEY_CHECKS=1;
SQL
"@ | Out-Null
    "CLEANUP completed"
}
