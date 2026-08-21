package com.ruoyi.web.service.xy;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.web.config.XyWechatPayProperties;

/** 微信支付 API v3：JSAPI 下单、退款、平台响应验签和回调解密。 */
@Service
public class XyWechatPayService
{
    private static final String API_HOST = "https://api.mch.weixin.qq.com";
    private static final long CALLBACK_MAX_AGE_SECONDS = 300;

    private final XyWechatPayProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;

    public XyWechatPayService(XyWechatPayProperties properties)
    {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.http = new RestTemplate(factory);
    }

    public boolean isDemoEnabled()
    {
        return properties.isDemoEnabled();
    }

    /**
     * 只有下单、回调验签和退款需要的生产配置都可用时，才向客户端宣告微信支付可用。
     * 这可避免“预下单成功但回调无法验签”或“收款后无法退款”的半配置状态。
     */
    public boolean isWechatPayConfigured()
    {
        return StringUtils.isNotEmpty(properties.getMchId())
                && StringUtils.isNotEmpty(properties.getMerchantSerialNo())
                && regularFile(properties.getPrivateKeyPath())
                && StringUtils.isNotEmpty(properties.getNotifyUrl())
                && StringUtils.isNotEmpty(properties.getRefundNotifyUrl())
                && StringUtils.isNotEmpty(properties.getAppId())
                && properties.getApiV3Key() != null
                && properties.getApiV3Key().getBytes(StandardCharsets.UTF_8).length == 32
                && hasWechatPayVerifier();
    }

    /** 验证已解密回调必须属于本商户/小程序，不能只依赖平台签名。 */
    public void validateNotificationIdentity(Map<?, ?> data)
    {
        if (data == null) throw new ServiceException("微信支付回调内容不完整");
        String mchId = data.get("mchid") == null ? null : String.valueOf(data.get("mchid"));
        if (StringUtils.isEmpty(mchId) || !mchId.equals(properties.getMchId()))
            throw new ServiceException("微信支付回调商户号不匹配");
        Object appIdValue = data.get("appid");
        if (appIdValue != null && !String.valueOf(appIdValue).equals(properties.getAppId()))
            throw new ServiceException("微信支付回调 AppID 不匹配");
    }

    public Map<String, Object> jsapi(String orderNo, String openid, int amountFen)
    {
        return jsapi(orderNo, openid, amountFen, "钓虾商城订单");
    }

    public Map<String, Object> jsapi(String orderNo, String openid, int amountFen, String description)
    {
        requireCommonConfig();
        if (StringUtils.isEmpty(properties.getNotifyUrl())) throw new ServiceException("微信支付回调地址未配置");
        if (StringUtils.isEmpty(openid) || amountFen <= 0) throw new ServiceException("支付参数不合法");
        try
        {
            String path = "/v3/pay/transactions/jsapi";
            Map<String, Object> body = new HashMap<>();
            body.put("appid", properties.getAppId());
            body.put("mchid", properties.getMchId());
            body.put("description", description);
            body.put("out_trade_no", orderNo);
            body.put("notify_url", properties.getNotifyUrl());
            body.put("amount", Collections.singletonMap("total", amountFen));
            body.put("payer", Collections.singletonMap("openid", openid));
            ResponseEntity<String> raw = post(path, body);
            Map<?, ?> response = mapper.readValue(raw.getBody(), Map.class);
            if (response.get("prepay_id") == null) throw new ServiceException("微信支付预下单失败");

            String prepay = String.valueOf(response.get("prepay_id"));
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String nonce = nonce();
            Map<String, Object> result = new HashMap<>();
            result.put("appId", properties.getAppId());
            result.put("timeStamp", timestamp);
            result.put("nonceStr", nonce);
            result.put("package", "prepay_id=" + prepay);
            result.put("signType", "RSA");
            result.put("paySign", sign(properties.getAppId() + "\n" + timestamp + "\n" + nonce + "\n" + prepay + "\n"));
            return result;
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("微信支付服务调用失败，请稍后重试");
        }
    }

    public Map<String, Object> refund(String transactionId, String refundNo, int totalFen, int refundFen)
    {
        requireCommonConfig();
        if (StringUtils.isEmpty(properties.getRefundNotifyUrl())) throw new ServiceException("微信退款回调地址未配置");
        if (StringUtils.isEmpty(transactionId) || totalFen <= 0 || refundFen <= 0 || refundFen > totalFen)
            throw new ServiceException("退款参数不合法");
        try
        {
            String path = "/v3/refund/domestic/refunds";
            Map<String, Object> amount = new HashMap<>();
            amount.put("refund", refundFen);
            amount.put("total", totalFen);
            amount.put("currency", "CNY");
            Map<String, Object> body = new HashMap<>();
            body.put("transaction_id", transactionId);
            body.put("out_refund_no", refundNo);
            body.put("reason", "用户售后退款");
            body.put("notify_url", properties.getRefundNotifyUrl());
            body.put("amount", amount);
            return mapper.readValue(post(path, body).getBody(), Map.class);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("微信退款申请失败，请稍后重试");
        }
    }

    public Map<?, ?> verifyCallback(String timestamp, String nonce, String signature, String serial, String body)
    {
        try
        {
            if (StringUtils.isEmpty(properties.getApiV3Key()) || properties.getApiV3Key().getBytes(StandardCharsets.UTF_8).length != 32)
                throw new ServiceException("微信支付 APIv3 密钥未正确配置");
            long callbackTime = Long.parseLong(timestamp);
            if (Math.abs(Instant.now().getEpochSecond() - callbackTime) > CALLBACK_MAX_AGE_SECONDS)
                throw new ServiceException("微信支付回调已过期");
            if (StringUtils.isEmpty(serial)) throw new ServiceException("微信支付回调缺少公钥标识");
            if (!verifyWechatPaySignature(timestamp + "\n" + nonce + "\n" + body + "\n", signature, serial))
                throw new ServiceException("微信支付回调验签失败");

            Map<?, ?> root = mapper.readValue(body, Map.class);
            Object resourceValue = root.get("resource");
            if (!(resourceValue instanceof Map)) throw new ServiceException("微信支付回调内容不完整");
            Map<?, ?> resource = (Map<?, ?>) resourceValue;
            String resourceNonce = String.valueOf(resource.get("nonce"));
            String ciphertext = String.valueOf(resource.get("ciphertext"));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(properties.getApiV3Key().getBytes(StandardCharsets.UTF_8), "AES"),
                    new GCMParameterSpec(128, resourceNonce.getBytes(StandardCharsets.UTF_8)));
            Object associatedData = resource.get("associated_data");
            if (associatedData != null)
                cipher.updateAAD(String.valueOf(associatedData).getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return mapper.readValue(new String(plaintext, StandardCharsets.UTF_8), Map.class);
        }
        catch (ServiceException ex)
        {
            throw ex;
        }
        catch (Exception ex)
        {
            throw new ServiceException("微信支付回调处理失败");
        }
    }

    private ResponseEntity<String> post(String path, Map<String, Object> body) throws Exception
    {
        String json = mapper.writeValueAsString(body);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = nonce();
        String authorization = "WECHATPAY2-SHA256-RSA2048 mchid=\"" + properties.getMchId()
                + "\",nonce_str=\"" + nonce + "\",timestamp=\"" + timestamp + "\",serial_no=\""
                + properties.getMerchantSerialNo() + "\",signature=\""
                + sign("POST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + json + "\n") + "\"";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", authorization);
        ResponseEntity<String> response = http.exchange(API_HOST + path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
        verifyResponse(response);
        return response;
    }

    private void verifyResponse(ResponseEntity<String> response) throws Exception
    {
        String timestamp = response.getHeaders().getFirst("Wechatpay-Timestamp");
        String nonce = response.getHeaders().getFirst("Wechatpay-Nonce");
        String signature = response.getHeaders().getFirst("Wechatpay-Signature");
        String serial = response.getHeaders().getFirst("Wechatpay-Serial");
        if (StringUtils.isEmpty(timestamp) || StringUtils.isEmpty(nonce) || StringUtils.isEmpty(signature) || StringUtils.isEmpty(serial))
            throw new ServiceException("微信支付响应缺少签名");
        String body = response.getBody() == null ? "" : response.getBody();
        if (!verifyWechatPaySignature(timestamp + "\n" + nonce + "\n" + body + "\n", signature, serial))
            throw new ServiceException("微信支付响应验签失败");
    }

    private boolean verifyWechatPaySignature(String message, String signature, String expectedSerial) throws Exception
    {
        if (StringUtils.isEmpty(expectedSerial)) throw new ServiceException("微信支付响应缺少公钥标识");
        PublicKey verificationKey;
        if (expectedSerial.startsWith("PUB_KEY_ID_"))
        {
            if (!expectedSerial.equals(properties.getPublicKeyId()) || !regularFile(properties.getPublicKeyPath()))
                return false;
            verificationKey = readPublicKey(properties.getPublicKeyPath());
        }
        else
        {
            if (!regularFile(properties.getPlatformCertificatePath())) return false;
            try (InputStream input = Files.newInputStream(Paths.get(properties.getPlatformCertificatePath())))
            {
                X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
                BigInteger serial = certificate.getSerialNumber();
                if (!serial.toString(16).equalsIgnoreCase(expectedSerial)) return false;
                verificationKey = certificate.getPublicKey();
            }
        }
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(verificationKey);
        verifier.update(message.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getDecoder().decode(signature));
    }

    private PublicKey readPublicKey(String path) throws Exception
    {
        String pem = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    private String sign(String value) throws Exception
    {
        String pem = new String(Files.readAllBytes(Paths.get(properties.getPrivateKeyPath())), StandardCharsets.UTF_8)
                .replaceAll("-----[^-]+-----", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private void requireCommonConfig()
    {
        if (!isWechatPayConfigured())
            throw new ServiceException("微信支付尚未完成生产配置");
    }

    private boolean regularFile(String path)
    {
        if (StringUtils.isEmpty(path)) return false;
        try { return Files.isRegularFile(Paths.get(path)); }
        catch (Exception ex) { return false; }
    }

    private boolean hasWechatPayVerifier()
    {
        boolean publicKeyReady = StringUtils.isNotEmpty(properties.getPublicKeyId())
                && properties.getPublicKeyId().startsWith("PUB_KEY_ID_")
                && regularFile(properties.getPublicKeyPath());
        return publicKeyReady || regularFile(properties.getPlatformCertificatePath());
    }

    private String nonce()
    {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
